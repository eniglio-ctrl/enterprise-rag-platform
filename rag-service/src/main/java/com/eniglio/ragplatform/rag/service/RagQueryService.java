package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.common.web.Citation;
import com.eniglio.ragplatform.common.web.RetrievedChunk;
import com.eniglio.ragplatform.rag.config.RagProperties;
import com.eniglio.ragplatform.rag.config.RagProperties.AvailableModel;
import com.eniglio.ragplatform.rag.dto.AskResponse;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.dto.DiagramResponse;
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

    private static final String EMPTY_DIAGRAM = "flowchart LR\n    A[Dados insuficientes para gerar um diagrama]";

    private static final Pattern BRACKET_LABEL = Pattern.compile("\\[([^\\[\\]]*)]");

    private static final Pattern MALFORMED_EDGE_LABEL = Pattern.compile("\\|([^|\\n]*)\\|>");

    private static final List<String> DIAGRAM_KEYWORDS = List.of(
            "diagrama", "diagram", "desenh", "draw", "fluxo", "flow", "arquitetura", "architecture",
            "imagem", "picture", "esquema", "flowchart", "grafico", "chart", "grafo", "mapa mental",
            "mindmap", "ilustra");

    private final HybridSearchService hybridSearchService;
    private final LlmRerankService llmRerankService;
    private final ChatClient ollamaChatClient;
    private final ChatClient lmStudioChatClient;
    private final LlmGateway llmGateway;
    private final RagProperties ragProperties;
    private final Counter answersGeneratedCounter;
    private final Counter diagramsGeneratedCounter;
    private final Timer answerTimer;
    private final Timer diagramTimer;

    public RagQueryService(HybridSearchService hybridSearchService, LlmRerankService llmRerankService,
                            @Qualifier("ollama") ChatClient ollamaChatClient,
                            @Qualifier("lmstudio") ChatClient lmStudioChatClient,
                            LlmGateway llmGateway, RagProperties ragProperties,
                            MeterRegistry meterRegistry) {
        this.hybridSearchService = hybridSearchService;
        this.llmRerankService = llmRerankService;
        this.ollamaChatClient = ollamaChatClient;
        this.lmStudioChatClient = lmStudioChatClient;
        this.llmGateway = llmGateway;
        this.ragProperties = ragProperties;
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
     * when the question itself asks for a diagram/drawing/flow, otherwise to
     * {@link #answer(String, String, boolean, boolean, String)}. Routing is a plain
     * keyword check on the question text rather than an extra LLM call, keeping it
     * fast and predictable.
     */
    public AskResponse ask(String question, String tenantId, boolean grounded, boolean rerank, String model) {
        if (wantsDiagram(question)) {
            DiagramResponse diagram = diagram(question, tenantId, model);
            return new AskResponse("diagram", null, diagram.mermaid(), diagram.citations(), null, diagram.model());
        }
        ChatResponse chat = answer(question, tenantId, grounded, rerank, model);
        return new AskResponse("answer", chat.answer(), null, chat.citations(), chat.groundedness(), chat.model());
    }

    private boolean wantsDiagram(String question) {
        String normalized = stripAccents(question.toLowerCase(Locale.ROOT));
        return DIAGRAM_KEYWORDS.stream().anyMatch(normalized::contains);
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
        ChatResponse response = answerTimer.record(() -> doAnswer(question, tenantId, grounded, rerank, model));
        answersGeneratedCounter.increment();
        return response;
    }

    private ChatResponse doAnswer(String question, String tenantId, boolean grounded, boolean rerank, String model) {
        int limit = rerank ? ragProperties.rerankCandidatePoolSize() : ragProperties.topK();
        List<Document> retrieved = hybridSearchService.search(question, tenantId, limit);
        if (rerank) {
            retrieved = llmRerankService.rerank(question, retrieved, ragProperties.topK());
        }

        if (retrieved.isEmpty()) {
            log.info("No relevant chunks found for question");
            return new ChatResponse(
                    "Não encontrei informação suficiente na base de conhecimento para responder a essa pergunta.",
                    List.of(), null, null);
        }

        String context = buildContext(retrieved);
        AvailableModel resolvedModel = resolveModel(model);

        String answer = callLlm(resolvedModel, () -> clientFor(resolvedModel).prompt()
                .system(spec -> spec.text(SYSTEM_TEMPLATE).param("context", context))
                .user(question)
                .options(modelOptions(resolvedModel, null))
                .call()
                .content());

        List<Citation> citations = buildCitations(retrieved);
        Groundedness groundedness = grounded ? checkGroundedness(context, answer, resolvedModel) : null;

        log.info("Answered question using {} retrieved chunks", retrieved.size());

        return new ChatResponse(answer, citations, groundedness, resolvedModel.id());
    }

    /**
     * Falls back to the first (default) entry in {@code rag.available-models}
     * (ADR 0017) when nothing was requested, or when the requested id isn't in that
     * list — a stale/mistyped id from a client shouldn't break the whole question.
     */
    private AvailableModel resolveModel(String requestedModel) {
        List<AvailableModel> available = ragProperties.availableModels();
        if (requestedModel == null || requestedModel.isBlank()) {
            return available.get(0);
        }
        return available.stream()
                .filter(m -> m.id().equals(requestedModel))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Requested model '{}' is not in rag.available-models, using the default",
                            requestedModel);
                    return available.get(0);
                });
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

    public DiagramResponse diagram(String question, String tenantId, String model) {
        DiagramResponse response = diagramTimer.record(() -> doDiagram(question, tenantId, model));
        diagramsGeneratedCounter.increment();
        return response;
    }

    private DiagramResponse doDiagram(String question, String tenantId, String model) {
        List<Document> retrieved = hybridSearchService.search(question, tenantId, ragProperties.topK());

        if (retrieved.isEmpty()) {
            log.info("No relevant chunks found for diagram question");
            return new DiagramResponse(EMPTY_DIAGRAM, List.of(), null);
        }

        String context = buildContext(retrieved);
        AvailableModel resolvedModel = resolveModel(model);

        String mermaid;
        String usedModel = resolvedModel.id();
        try {
            String raw = callLlm(resolvedModel, () -> clientFor(resolvedModel).prompt()
                    .system(spec -> spec.text(DIAGRAM_SYSTEM_TEMPLATE).param("context", context))
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

        log.info("Generated diagram using {} retrieved chunks", retrieved.size());

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
