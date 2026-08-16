package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.gateway.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Judges candidate relevance with a single batched LLM call (ADR 0012) — one call
 * scoring every candidate at once, via structured output, never one call per
 * candidate. Opt-in per request ({@code ChatRequest.rerank}): it's a full extra
 * Ollama round trip on top of hybrid search + generation, the same latency tradeoff
 * already made for groundedness (ADR 0008).
 */
@Service
public class LlmRerankService {

    private static final Logger log = LoggerFactory.getLogger(LlmRerankService.class);

    private static final String RERANK_SYSTEM_TEMPLATE = """
            Você avalia a relevância de trechos de documentos para uma pergunta.
            Para CADA trecho numerado abaixo, dê uma nota de 0 a 10 indicando o quanto
            ele ajuda a responder a pergunta (10 = totalmente relevante, 0 = irrelevante).
            Responda com uma nota para todo trecho recebido, sem pular nenhum índice.

            PERGUNTA:
            {question}

            TRECHOS:
            {candidates}
            """;

    private final ChatClient chatClient;
    private final LlmGateway llmGateway;

    // Always Ollama, regardless of the chat model selected for generation (ADR 0017):
    // rerank's own output-reliability tradeoffs (structured-output fallback, ADR 0012)
    // are already handled separately and don't need multiplying by provider choice.
    public LlmRerankService(@Qualifier("ollama") ChatClient chatClient, LlmGateway llmGateway) {
        this.chatClient = chatClient;
        this.llmGateway = llmGateway;
    }

    public List<Document> rerank(String question, List<Document> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        String candidatesText = buildCandidatesText(candidates);
        RerankResponse response;
        try {
            response = llmGateway.callOllama(() -> chatClient.prompt()
                    .system(spec -> spec.text(RERANK_SYSTEM_TEMPLATE)
                            .param("question", question)
                            .param("candidates", candidatesText))
                    .user("Avalie a relevância de cada trecho numerado.")
                    .options(OllamaOptions.builder().temperature(0.0).build())
                    .call()
                    .entity(RerankResponse.class));
        } catch (Exception e) {
            log.warn("Rerank call failed, falling back to the pre-rerank (RRF) order", e);
            return candidates.stream().limit(topK).toList();
        }

        // A smaller local model doesn't always follow the requested output schema —
        // observed in practice: llama3.1 returning JSON that parses but has no
        // "scores" array at all. That's a fallback case, not an error to propagate;
        // the caller already has a perfectly usable RRF-ordered list without rerank.
        if (response == null || response.scores() == null || response.scores().isEmpty()) {
            log.warn("Model returned no usable rerank scores, falling back to the pre-rerank (RRF) order");
            return candidates.stream().limit(topK).toList();
        }

        Map<Integer, Integer> scoreByIndex = response.scores().stream()
                .collect(Collectors.toMap(CandidateScore::index, CandidateScore::score, (a, b) -> a));

        log.info("Reranked {} candidates down to top {}", candidates.size(), topK);

        return IntStream.range(0, candidates.size())
                .boxed()
                .sorted(Comparator.<Integer>comparingInt(i -> scoreByIndex.getOrDefault(i, 0)).reversed())
                .limit(topK)
                .map(i -> candidates.get(i).mutate()
                        .score(scoreByIndex.getOrDefault(i, 0) / 10.0)
                        .build())
                .toList();
    }

    private String buildCandidatesText(List<Document> candidates) {
        return IntStream.range(0, candidates.size())
                .mapToObj(i -> "[%d] %s".formatted(i, candidates.get(i).getText()))
                .collect(Collectors.joining("\n\n"));
    }

    // Package-private, not private: Spring AI's structured-output schema generation
    // reflects over this class, which is more reliable with default access than with
    // a private nested record.
    record CandidateScore(int index, int score) {
    }

    record RerankResponse(List<CandidateScore> scores) {
    }
}
