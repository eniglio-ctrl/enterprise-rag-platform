package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.config.RagProperties;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.dto.Citation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
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

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final RagProperties ragProperties;

    public RagQueryService(VectorStore vectorStore, ChatClient chatClient, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.ragProperties = ragProperties;
    }

    public ChatResponse answer(String question) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(ragProperties.topK())
                .similarityThreshold(ragProperties.similarityThreshold())
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
