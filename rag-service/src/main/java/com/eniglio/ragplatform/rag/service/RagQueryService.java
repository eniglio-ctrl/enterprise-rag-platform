package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.common.web.Citation;
import com.eniglio.ragplatform.common.web.RetrievedChunk;
import com.eniglio.ragplatform.rag.config.RagProperties;
import com.eniglio.ragplatform.rag.config.RagProperties.AvailableModel;
import com.eniglio.ragplatform.rag.dto.AskResponse;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.dto.DiagramResponse;
import com.eniglio.ragplatform.rag.dto.ContextRelevance;
import com.eniglio.ragplatform.rag.dto.Groundedness;
import com.eniglio.ragplatform.rag.gateway.LlmGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class RagQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);

    private static final String SYSTEM_TEMPLATE = """
            Você é um assistente técnico que responde exclusivamente com base no CONTEXTO abaixo.
            Regras:
            - Se a resposta não estiver no contexto, diga claramente que não encontrou informação suficiente.
            - Sempre cite as fontes usando os números entre colchetes que aparecem no contexto, ex: [1], [2].
            - Seja direto, técnico e responda no mesmo idioma da pergunta.

            CONTEXTO:
            {context}
            """;

    private static final String DIAGRAM_SYSTEM_TEMPLATE = """
            Você gera diagramas de arquitetura em Mermaid.js a partir do CONTEXTO abaixo.
            Regras:
            - Responda apenas com a definição Mermaid, começando com "flowchart LR" ou "flowchart TD".
            - Não inclua blocos de código (sem ```), comentários ou qualquer texto fora da definição.
            - Use IDs curtos para os nós (A, B, C...) e rótulos descritivos entre colchetes, sempre
              com o texto entre aspas duplas, ex: A["Amazon S3"] ou B["Multi-AZ (alta disponibilidade)"].
              Isso é obrigatório sempre que o rótulo tiver parênteses, vírgulas ou outra pontuação.
            - Rótulo em seta (edge label) usa a sintaxe A -->|texto| B — nunca coloque um ">" extra
              depois do segundo pipe (A -->|texto|> B está errado).
            - Represente apenas os serviços/componentes e o fluxo entre eles que estão explicitamente
              descritos no contexto; não invente serviços ou conexões que não aparecem no texto.
            - A pergunta pode ser mais genérica do que o contexto (ex.: "funcionamento da AWS"
              quando o contexto fala de um caso específico de recuperação de desastres). Nesse caso,
              monte o melhor diagrama possível com os componentes técnicos que o contexto realmente
              descreve, em vez de recusar.
            - Só responda com o nó de dados insuficientes se o contexto não tiver NENHUM componente,
              serviço ou passo técnico para representar:
              flowchart LR
                  A[Dados insuficientes para gerar um diagrama]

            CONTEXTO:
            {context}
            """;

    private static final String GROUNDEDNESS_SYSTEM_TEMPLATE = """
            Dado o CONTEXTO e a RESPOSTA abaixo, responda apenas "SUPORTADA" ou "NAO_SUPORTADA".
            Considere SUPORTADA se todas as afirmações da resposta podem ser verificadas no contexto.
            Considere NAO_SUPORTADA se a resposta contém qualquer afirmação que não aparece no contexto.

            CONTEXTO:
            {context}
            """;

    // Multi-LLM Phase 8 (RAG quality deep-dive): faithfulness (above) asks "is the
    // *answer* backed by the context"; this asks the narrower, independent question
    // "is *this one retrieved chunk* actually useful for this question" - a bad
    // retrieval can still produce a faithful-looking answer if the model happens to
    // already know the fact, so the two checks catch different failure modes.
    private static final String CONTEXT_RELEVANCE_SYSTEM_TEMPLATE = """
            Dada a PERGUNTA abaixo e um TRECHO de documento, responda apenas "RELEVANTE" ou "IRRELEVANTE".
            Considere RELEVANTE se o trecho contém informação que ajudaria a responder a pergunta,
            mesmo que só parcialmente.
            Considere IRRELEVANTE se o trecho não tem relação nenhuma com o que a pergunta pede.

            PERGUNTA:
            {question}
            """;

    // Prepended to SYSTEM_TEMPLATE/DIAGRAM_SYSTEM_TEMPLATE only when a question has an
    // attached image (never included otherwise, to avoid cluttering the common path).
    // The "[IMAGEM]" block is deliberately NOT one of the numbered "[1]", "[2]"...
    // context entries buildContext() produces from retrieved chunks — those numbers
    // must line up 1:1 with the citations array returned to the caller, and an image
    // description has no corresponding Citation. Telling the model explicitly not to
    // cite it with a bracket number avoids it inventing a citation index that doesn't
    // exist in the response.
    private static final String IMAGE_CONTEXT_INSTRUCTIONS = """
            Uma imagem foi anexada à pergunta e descrita automaticamente por um modelo de visão — \
            essa descrição aparece no CONTEXTO como "[IMAGEM]", não é uma fonte numerada como as \
            demais e não deve ser citada com colchetes numéricos.

            """;

    private static final String EMPTY_DIAGRAM = "flowchart LR\n    A[Dados insuficientes para gerar um diagrama]";

    private static final Pattern BRACKET_LABEL = Pattern.compile("\\[([^\\[\\]]*)]");

    private static final Pattern MALFORMED_EDGE_LABEL = Pattern.compile("\\|([^|\\n]*)\\|>");

    /**
     * Replaced a fixed keyword list (found broken by a real user report — "O que tem
     * nessa imagem?" contains "imagem", which used to be a diagram-trigger keyword, so
     * every such question about an attached photo silently misrouted to diagram
     * generation instead of actually describing the image). A word appearing in a
     * question says nothing reliable about intent — "imagem" can mean "describe this
     * photo" or "draw me a picture of X" depending entirely on context a fixed list
     * can't capture. This costs one extra, cheap, temperature-0 LLM call per question
     * (previously routing was free), traded deliberately for actually understanding
     * what's being asked instead of pattern-matching words.
     */
    private static final String ROUTING_SYSTEM_TEMPLATE = """
            Classifique a intenção por trás da pergunta do usuário abaixo em exatamente uma palavra:
            "DIAGRAMA" ou "RESPOSTA". Não escreva mais nada além dessa palavra.

            Responda "DIAGRAMA" apenas quando o usuário pedir explicitamente para desenhar, gerar,
            montar ou visualizar um diagrama, fluxograma, mapa mental, ou a arquitetura/fluxo de um
            processo em formato de diagrama.

            Responda "RESPOSTA" em todos os outros casos — incluindo perguntas sobre o conteúdo de
            uma imagem ou anexo (ex.: "o que tem nessa imagem?", "descreva essa captura de tela",
            "o que esse anexo mostra?"), mesmo que a pergunta contenha palavras como "imagem",
            "diagrama" ou "arquitetura" de forma incidental. O que importa é a intenção real por
            trás da pergunta, nunca a simples presença de uma palavra específica.
            """;

    private final HybridSearchService hybridSearchService;
    private final LlmRerankService llmRerankService;
    private final ChatClient ollamaChatClient;
    private final ChatClient lmStudioChatClient;
    private final LlmGateway llmGateway;
    private final RagProperties ragProperties;
    private final VisionDescriptionService visionDescriptionService;
    private final Counter answersGeneratedCounter;
    private final Counter diagramsGeneratedCounter;
    private final Timer answerTimer;
    private final Timer diagramTimer;

    public RagQueryService(HybridSearchService hybridSearchService, LlmRerankService llmRerankService,
                            @Qualifier("ollama") ChatClient ollamaChatClient,
                            @Qualifier("lmstudio") ChatClient lmStudioChatClient,
                            LlmGateway llmGateway, RagProperties ragProperties,
                            VisionDescriptionService visionDescriptionService,
                            MeterRegistry meterRegistry) {
        this.hybridSearchService = hybridSearchService;
        this.llmRerankService = llmRerankService;
        this.ollamaChatClient = ollamaChatClient;
        this.lmStudioChatClient = lmStudioChatClient;
        this.llmGateway = llmGateway;
        this.ragProperties = ragProperties;
        this.visionDescriptionService = visionDescriptionService;
        this.answersGeneratedCounter = Counter.builder("rag.answers.generated")
                .description("Number of text answers generated")
                .register(meterRegistry);
        this.diagramsGeneratedCounter = Counter.builder("rag.diagrams.generated")
                .description("Number of Mermaid diagrams generated")
                .register(meterRegistry);
        this.answerTimer = Timer.builder("rag.answer.generation.duration")
                .description("Time to answer a question, from retrieval to generated answer")
                .register(meterRegistry);
        this.diagramTimer = Timer.builder("rag.diagram.generation.duration")
                .description("Time to generate a diagram, from retrieval to Mermaid output")
                .register(meterRegistry);
    }

    /**
     * Single entry point for the UI: routes to {@link #diagram(String, String, String)}
     * when the question's actual intent is to get a diagram, otherwise to
     * {@link #answer(String, String, boolean, boolean, String)}. Routing is a real,
     * cheap (temperature 0, single-word output) LLM classification call — see
     * {@link #ROUTING_SYSTEM_TEMPLATE} for why a keyword check isn't reliable enough.
     */
    public AskResponse ask(String question, String tenantId, boolean grounded, boolean rerank, String model) {
        return ask(question, tenantId, grounded, rerank, model, null, null);
    }

    /**
     * Same routing as the 5-arg {@link #ask(String, String, boolean, boolean, String)},
     * plus an optional image attached to this single question (never indexed —
     * described once via {@link VisionDescriptionService} and folded into whichever
     * path ({@link #answer}/{@link #diagram}) the question routes to). {@code
     * imageBytes}/{@code imageMimeType} are both null when no image was attached.
     */
    public AskResponse ask(String question, String tenantId, boolean grounded, boolean rerank, String model,
                            byte[] imageBytes, MimeType imageMimeType) {
        String imageDescription = describeImage(imageBytes, imageMimeType);
        AvailableModel resolvedModel = resolveModel(model);
        if (wantsDiagram(question, imageDescription, resolvedModel)) {
            DiagramResponse diagram = diagram(question, tenantId, model, imageDescription);
            return new AskResponse("diagram", null, diagram.mermaid(), diagram.citations(), null, diagram.model());
        }
        ChatResponse chat = answer(question, tenantId, grounded, rerank, model, imageDescription);
        return new AskResponse("answer", chat.answer(), null, chat.citations(), chat.groundedness(), chat.model());
    }

    private String describeImage(byte[] imageBytes, MimeType imageMimeType) {
        return imageBytes == null ? null : visionDescriptionService.describe(imageBytes, imageMimeType);
    }

    /**
     * A real classification call, not a keyword match (see {@link #ROUTING_SYSTEM_TEMPLATE}).
     * When an image is attached, the model only learns that one was attached — not its
     * description — since the routing decision only needs to know an image exists, and
     * keeping this prompt minimal keeps the call fast.
     */
    private boolean wantsDiagram(String question, String imageDescription, AvailableModel resolvedModel) {
        String userMessage = imageDescription == null
                ? question
                : question + "\n\n[Uma imagem foi anexada a esta pergunta.]";
        String verdict = callLlm(resolvedModel, () -> clientFor(resolvedModel).prompt()
                .system(ROUTING_SYSTEM_TEMPLATE)
                .user(userMessage)
                .options(modelOptions(resolvedModel, 0.0))
                .call()
                .content());
        return stripAccents((verdict == null ? "" : verdict).toUpperCase(Locale.ROOT)).contains("DIAGRAMA");
    }

    /**
     * Keyword matching shouldn't care whether the user typed "gráfico" or "grafico" —
     * strip accents from the question before matching so a single unaccented keyword
     * (e.g. "grafico") covers both.
     */
    private String stripAccents(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "");
    }

    public ChatResponse answer(String question, String tenantId, boolean grounded, boolean rerank, String model) {
        return answer(question, tenantId, grounded, rerank, model, null);
    }

    private ChatResponse answer(String question, String tenantId, boolean grounded, boolean rerank, String model,
                                 String imageDescription) {
        ChatResponse response = answerTimer.record(
                () -> doAnswer(question, tenantId, grounded, rerank, model, imageDescription));
        answersGeneratedCounter.increment();
        return response;
    }

    private ChatResponse doAnswer(String question, String tenantId, boolean grounded, boolean rerank, String model,
                                   String imageDescription) {
        int limit = rerank ? ragProperties.rerankCandidatePoolSize() : ragProperties.topK();
        List<Document> retrieved = hybridSearchService.search(question, tenantId, limit);
        if (rerank) {
            retrieved = llmRerankService.rerank(question, retrieved, ragProperties.topK());
        }

        // An attached image can fully answer the question on its own (e.g. "what does
        // this diagram show?") even with zero relevant chunks in the knowledge base —
        // only short-circuit to "not enough information" when there's neither.
        if (retrieved.isEmpty() && imageDescription == null) {
            log.info("No relevant chunks found for question");
            return new ChatResponse(
                    "Não encontrei informação suficiente na base de conhecimento para responder a essa pergunta.",
                    List.of(), null, null);
        }

        String context = buildContext(retrieved);
        String systemTemplate = SYSTEM_TEMPLATE;
        if (imageDescription != null) {
            context = withImageContext(context, imageDescription);
            systemTemplate = IMAGE_CONTEXT_INSTRUCTIONS + SYSTEM_TEMPLATE;
        }
        String finalContext = context;
        String finalSystemTemplate = systemTemplate;
        AvailableModel resolvedModel = resolveModel(model);

        String answer = callLlm(resolvedModel, () -> clientFor(resolvedModel).prompt()
                .system(spec -> spec.text(finalSystemTemplate).param("context", finalContext))
                .user(question)
                .options(modelOptions(resolvedModel, null))
                .call()
                .content());

        List<Citation> citations = buildCitations(retrieved);
        Groundedness groundedness = grounded ? checkGroundedness(context, answer, resolvedModel) : null;

        log.info("Answered question using {} retrieved chunks{}", retrieved.size(),
                imageDescription != null ? " and an attached image" : "");

        return new ChatResponse(answer, citations, groundedness, resolvedModel.id());
    }

    /**
     * Prepends the image's description as a distinct, non-numbered "[IMAGEM]" block
     * ahead of the numbered retrieved-chunk context — see
     * {@link #IMAGE_CONTEXT_INSTRUCTIONS} for why it must never share numbering with
     * the citations array.
     */
    private String withImageContext(String context, String imageDescription) {
        String imageBlock = "[IMAGEM] " + imageDescription;
        return context.isBlank() ? imageBlock : imageBlock + "\n\n" + context;
    }

    /**
     * Falls back to the first (default) entry in {@code rag.available-models}
     * (ADR 0017) when nothing was requested, or when the requested id isn't in that
     * list — a stale/mistyped id from a client shouldn't break the whole question.
     * Always returns a genuinely callable model: the "auto" sentinel entry (ADR 0025)
     * is resolved to {@link #firstConcreteModel} right here, once, so every caller —
     * {@link #clientFor}, {@link #callLlm}, {@link #modelOptions}, and the {@code
     * model} field returned to the client — automatically sees a real provider and
     * id, never the literal string "auto".
     */
    private AvailableModel resolveModel(String requestedModel) {
        List<AvailableModel> available = ragProperties.availableModels();
        AvailableModel selected;
        if (requestedModel == null || requestedModel.isBlank()) {
            selected = available.get(0);
        } else {
            selected = available.stream()
                    .filter(m -> m.id().equals(requestedModel))
                    .findFirst()
                    .orElseGet(() -> {
                        // Strip CR/LF before logging: requestedModel comes straight from the
                        // request body (ADR 0017), so a value crafted with newlines could
                        // otherwise forge fake-looking extra log lines (log injection, CWE-117).
                        String sanitized = requestedModel.replaceAll("[\r\n]", "_");
                        log.warn("Requested model '{}' is not in rag.available-models, using the default",
                                sanitized);
                        return available.get(0);
                    });
        }
        return "auto".equals(selected.provider()) ? firstConcreteModel(available) : selected;
    }

    /**
     * The model "auto" actually means today (ADR 0025): the first entry in
     * {@code rag.available-models} that isn't itself the "auto" sentinel. No
     * question-dependent logic yet — deliberately, since there's currently only
     * one or two locally/self-hosted models configured per environment, not a real
     * pool of providers worth choosing between intelligently. See
     * {@code docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md} for where that would evolve.
     */
    private AvailableModel firstConcreteModel(List<AvailableModel> available) {
        return available.stream()
                .filter(m -> !"auto".equals(m.provider()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "rag.available-models has no concrete (non-auto) entry configured"));
    }

    /** Picks the {@code ChatClient} backing {@code model}'s provider (ADR 0017). */
    private ChatClient clientFor(AvailableModel model) {
        return "lmstudio".equals(model.provider()) ? lmStudioChatClient : ollamaChatClient;
    }

    /**
     * Dispatches through the gateway method matching {@code model}'s provider, so
     * each provider's circuit breaker/retry is tracked separately (ADR 0017) — must be
     * an external call through the {@code LlmGateway} bean, never {@code this.*},
     * or Resilience4j's proxy-based interception silently does nothing (ADR 0009's
     * self-invocation gotcha).
     */
    private <T> T callLlm(AvailableModel model, Supplier<T> chatCall) {
        return "lmstudio".equals(model.provider()) ? llmGateway.callLmStudio(chatCall) : llmGateway.callOllama(chatCall);
    }

    /**
     * Only non-null fields here override the {@code ChatClient}'s configured default
     * options bean — Spring AI merges per-call options with it field by field, so
     * leaving {@code temperature} null when not overriding keeps whatever the default
     * bean already has. {@code model} is always set explicitly (ADR 0017): unlike
     * temperature, which has one shared default per provider, the model id is the
     * whole point of this override and {@link #resolveModel} always returns a concrete
     * one, never a "use whatever's default" signal.
     */
    private ChatOptions modelOptions(AvailableModel model, Double temperature) {
        if ("lmstudio".equals(model.provider())) {
            OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model.id());
            if (temperature != null) {
                builder.temperature(temperature);
            }
            return builder.build();
        }
        OllamaOptions.Builder builder = OllamaOptions.builder().model(model.id());
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    /**
     * Retrieval only, no generation — used by chat-service (ADR 0013) so it can build
     * its own conversation-aware answer without paying for (and discarding) a full
     * generation call here too. Returns full chunk text ({@link RetrievedChunk}), not
     * the truncated {@link Citation#snippet()} — a caller using this as real
     * generation context needs the whole chunk, not a display-sized preview.
     */
    public List<RetrievedChunk> retrieve(String question, String tenantId) {
        List<Document> retrieved = hybridSearchService.search(question, tenantId, ragProperties.topK());
        return retrieved.stream()
                .map(doc -> new RetrievedChunk(
                        String.valueOf(doc.getMetadata().getOrDefault("source", "unknown")),
                        toInteger(doc.getMetadata().get("chunkIndex")),
                        doc.getScore(),
                        doc.getText()))
                .toList();
    }

    /**
     * A second LLM call asking whether the answer is actually backed by the retrieved
     * context (ADR 0008) — opt-in per request since it roughly doubles latency.
     * Temperature 0.0 for the same reason as diagram generation: this is a
     * classification, not prose, so deterministic output is worth more than variety.
     */
    private Groundedness checkGroundedness(String context, String answer, AvailableModel resolvedModel) {
        String verdict = callLlm(resolvedModel, () -> clientFor(resolvedModel).prompt()
                .system(spec -> spec.text(GROUNDEDNESS_SYSTEM_TEMPLATE).param("context", context))
                .user("RESPOSTA:\n" + answer)
                .options(modelOptions(resolvedModel, 0.0))
                .call()
                .content());
        return parseGroundedness(verdict);
    }

    /**
     * Public entry point for {@code RagQualityBenchmark} (Multi-LLM Phase 8) to reuse
     * this exact faithfulness check outside the live {@code /api/v1/ask} request
     * cycle — resolves the default model rather than requiring a caller that only has
     * a (question, answer, context) triple to also know about model selection.
     */
    public Groundedness checkGroundedness(String context, String answer) {
        return checkGroundedness(context, answer, resolveModel(null));
    }

    private Groundedness parseGroundedness(String verdict) {
        String normalized = stripAccents((verdict == null ? "" : verdict).toUpperCase(Locale.ROOT));
        if (normalized.contains("NAO_SUPORTADA") || normalized.contains("NAO SUPORTADA")) {
            return Groundedness.NOT_SUPPORTED;
        }
        if (normalized.contains("SUPORTADA")) {
            return Groundedness.SUPPORTED;
        }
        log.warn("Unexpected groundedness verdict from model, defaulting to SUPPORTED: {}", verdict);
        return Groundedness.SUPPORTED;
    }

    /**
     * Multi-LLM Phase 8: independent of faithfulness above — this judges one
     * retrieved chunk against the question alone, with no knowledge of what the
     * final answer said. A bad retrieval can still yield a faithful-looking answer
     * (the model already "knew" the fact), so context relevance catches a different
     * failure than groundedness does.
     */
    public ContextRelevance checkContextRelevance(String question, String chunkContent) {
        AvailableModel resolvedModel = resolveModel(null);
        String verdict = callLlm(resolvedModel, () -> clientFor(resolvedModel).prompt()
                .system(spec -> spec.text(CONTEXT_RELEVANCE_SYSTEM_TEMPLATE).param("question", question))
                .user("TRECHO:\n" + chunkContent)
                .options(modelOptions(resolvedModel, 0.0))
                .call()
                .content());
        return parseContextRelevance(verdict);
    }

    private ContextRelevance parseContextRelevance(String verdict) {
        String normalized = stripAccents((verdict == null ? "" : verdict).toUpperCase(Locale.ROOT));
        // "IRRELEVANTE" contains "RELEVANTE" as a substring - same ordering pitfall
        // already handled in parseGroundedness above, checked first here too.
        if (normalized.contains("IRRELEVANTE")) {
            return ContextRelevance.NOT_RELEVANT;
        }
        if (normalized.contains("RELEVANTE")) {
            return ContextRelevance.RELEVANT;
        }
        log.warn("Unexpected context-relevance verdict from model, defaulting to RELEVANT: {}", verdict);
        return ContextRelevance.RELEVANT;
    }

    public DiagramResponse diagram(String question, String tenantId, String model) {
        return diagram(question, tenantId, model, null);
    }

    private DiagramResponse diagram(String question, String tenantId, String model, String imageDescription) {
        DiagramResponse response = diagramTimer.record(() -> doDiagram(question, tenantId, model, imageDescription));
        diagramsGeneratedCounter.increment();
        return response;
    }

    private DiagramResponse doDiagram(String question, String tenantId, String model, String imageDescription) {
        List<Document> retrieved = hybridSearchService.search(question, tenantId, ragProperties.topK());

        // Same reasoning as doAnswer: an attached image (e.g. a screenshot of an
        // architecture) can supply everything needed to draw a diagram even with zero
        // matching chunks in the knowledge base.
        if (retrieved.isEmpty() && imageDescription == null) {
            log.info("No relevant chunks found for diagram question");
            return new DiagramResponse(EMPTY_DIAGRAM, List.of(), null);
        }

        String context = buildContext(retrieved);
        String systemTemplate = DIAGRAM_SYSTEM_TEMPLATE;
        if (imageDescription != null) {
            context = withImageContext(context, imageDescription);
            systemTemplate = IMAGE_CONTEXT_INSTRUCTIONS + DIAGRAM_SYSTEM_TEMPLATE;
        }
        String finalContext = context;
        String finalSystemTemplate = systemTemplate;
        AvailableModel resolvedModel = resolveModel(model);

        String mermaid;
        String usedModel = resolvedModel.id();
        try {
            String raw = callLlm(resolvedModel, () -> clientFor(resolvedModel).prompt()
                    .system(spec -> spec.text(finalSystemTemplate).param("context", finalContext))
                    .user(question)
                    .options(modelOptions(resolvedModel, 0.0))
                    .call()
                    .content());
            mermaid = fixMalformedEdgeLabels(quoteBracketLabels(stripCodeFences(raw)));
        } catch (Exception e) {
            log.warn("Failed to generate a diagram from the model response", e);
            mermaid = EMPTY_DIAGRAM;
            usedModel = null;
        }

        List<Citation> citations = buildCitations(retrieved);

        log.info("Generated diagram using {} retrieved chunks{}", retrieved.size(),
                imageDescription != null ? " and an attached image" : "");

        return new DiagramResponse(mermaid, citations, usedModel);
    }

    private String stripCodeFences(String text) {
        if (text == null || text.isBlank()) {
            return EMPTY_DIAGRAM;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\r?\\n?", "");
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.trim();
    }

    /**
     * The model doesn't always quote node labels as instructed, and an unquoted label
     * containing punctuation like parentheses breaks Mermaid's parser (e.g. "A[Multi-AZ
     * (HA)]"). Force every rectangle-node label into a quoted string, which Mermaid
     * accepts regardless of what punctuation it contains.
     */
    private String quoteBracketLabels(String mermaid) {
        Matcher matcher = BRACKET_LABEL.matcher(mermaid);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String label = matcher.group(1).trim();
            String quoted = label.startsWith("\"") && label.endsWith("\"")
                    ? label
                    : "\"" + label.replace("\"", "'") + "\"";
            matcher.appendReplacement(result, Matcher.quoteReplacement("[" + quoted + "]"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * The model sometimes writes edge labels as {@code -->|texto|>} (a stray ">" after
     * the closing pipe) instead of the valid {@code -->|texto|}, which Mermaid's parser
     * rejects. Strip the extra ">" wherever it directly follows a pipe-delimited label.
     */
    private String fixMalformedEdgeLabels(String mermaid) {
        return MALFORMED_EDGE_LABEL.matcher(mermaid).replaceAll("|$1|");
    }

    private String buildContext(List<Document> documents) {
        return IntStream.range(0, documents.size())
                .mapToObj(i -> "[%d] %s".formatted(i + 1, documents.get(i).getText()))
                .collect(Collectors.joining("\n\n"));
    }

    private List<Citation> buildCitations(List<Document> documents) {
        return documents.stream()
                .map(doc -> new Citation(
                        String.valueOf(doc.getMetadata().getOrDefault("source", "unknown")),
                        toInteger(doc.getMetadata().get("chunkIndex")),
                        doc.getScore(),
                        snippet(doc.getText())))
                .toList();
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
