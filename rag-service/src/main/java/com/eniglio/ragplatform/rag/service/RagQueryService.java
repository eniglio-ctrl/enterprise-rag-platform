package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.config.RagProperties;
import com.eniglio.ragplatform.rag.dto.AskResponse;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.dto.Citation;
import com.eniglio.ragplatform.rag.dto.DiagramResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
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

    private static final String EMPTY_DIAGRAM = "flowchart LR\n    A[Dados insuficientes para gerar um diagrama]";

    private static final Pattern BRACKET_LABEL = Pattern.compile("\\[([^\\[\\]]*)]");

    private static final Pattern MALFORMED_EDGE_LABEL = Pattern.compile("\\|([^|\\n]*)\\|>");

    private static final List<String> DIAGRAM_KEYWORDS = List.of(
            "diagrama", "diagram", "desenh", "draw", "fluxo", "flow", "arquitetura", "architecture",
            "imagem", "picture", "esquema", "flowchart", "grafico", "chart", "grafo", "mapa mental",
            "mindmap", "ilustra");

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final RagProperties ragProperties;

    public RagQueryService(VectorStore vectorStore, ChatClient chatClient, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.ragProperties = ragProperties;
    }

    /**
     * Single entry point for the UI: routes to {@link #diagram(String, String)} when
     * the question itself asks for a diagram/drawing/flow, otherwise to
     * {@link #answer(String, String)}. Routing is a plain keyword check on the
     * question text rather than an extra LLM call, keeping it fast and predictable.
     */
    public AskResponse ask(String question, String tenantId) {
        if (wantsDiagram(question)) {
            DiagramResponse diagram = diagram(question, tenantId);
            return new AskResponse("diagram", null, diagram.mermaid(), diagram.citations());
        }
        ChatResponse chat = answer(question, tenantId);
        return new AskResponse("answer", chat.answer(), null, chat.citations());
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

    public ChatResponse answer(String question, String tenantId) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(ragProperties.topK())
                .similarityThreshold(ragProperties.similarityThreshold())
                .filterExpression(tenantFilter(tenantId))
                .build();

        List<Document> retrieved = vectorStore.similaritySearch(searchRequest);

        if (retrieved.isEmpty()) {
            log.info("No relevant chunks found for question");
            return new ChatResponse(
                    "Não encontrei informação suficiente na base de conhecimento para responder a essa pergunta.",
                    List.of());
        }

        String context = buildContext(retrieved);

        String answer = chatClient.prompt()
                .system(spec -> spec.text(SYSTEM_TEMPLATE).param("context", context))
                .user(question)
                .call()
                .content();

        List<Citation> citations = buildCitations(retrieved);

        log.info("Answered question using {} retrieved chunks", retrieved.size());

        return new ChatResponse(answer, citations);
    }

    public DiagramResponse diagram(String question, String tenantId) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(ragProperties.topK())
                .similarityThreshold(ragProperties.similarityThreshold())
                .filterExpression(tenantFilter(tenantId))
                .build();

        List<Document> retrieved = vectorStore.similaritySearch(searchRequest);

        if (retrieved.isEmpty()) {
            log.info("No relevant chunks found for diagram question");
            return new DiagramResponse(EMPTY_DIAGRAM, List.of());
        }

        String context = buildContext(retrieved);

        String mermaid;
        try {
            String raw = chatClient.prompt()
                    .system(spec -> spec.text(DIAGRAM_SYSTEM_TEMPLATE).param("context", context))
                    .user(question)
                    .options(OllamaOptions.builder().temperature(0.0).build())
                    .call()
                    .content();
            mermaid = fixMalformedEdgeLabels(quoteBracketLabels(stripCodeFences(raw)));
        } catch (Exception e) {
            log.warn("Failed to generate a diagram from the model response", e);
            mermaid = EMPTY_DIAGRAM;
        }

        List<Citation> citations = buildCitations(retrieved);

        log.info("Generated diagram using {} retrieved chunks", retrieved.size());

        return new DiagramResponse(mermaid, citations);
    }

    /**
     * Tenant is the data-isolation boundary (ADR 0007): every user within a tenant can
     * search that tenant's whole knowledge base, so only tenantId is filtered here.
     * userId is still recorded on every chunk at ingestion for attribution/audit.
     */
    private Filter.Expression tenantFilter(String tenantId) {
        return new FilterExpressionBuilder().eq("tenantId", tenantId).build();
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
