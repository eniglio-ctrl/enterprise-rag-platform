package com.eniglio.ragplatform.rag.gateway;

import com.eniglio.ragplatform.rag.config.FallbackProviderProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Multi-LLM Phase 2a: a direct REST client, not a Spring AI {@code ChatModel} —
 * Spring AI 1.0.0 ships no plain-API-key Gemini integration. Its only Gemini support
 * is {@code spring-ai-starter-model-vertex-ai-gemini}, which wraps **Vertex AI**, a
 * different Google product authenticated via a GCP project + service-account
 * credentials, not a bare API key. The key this project actually has (from
 * `https://aistudio.google.com/apikey`, free, no card) is for the **Generative
 * Language API** (`generativelanguage.googleapis.com`) instead — confirmed for real
 * by calling both APIs directly before writing this class, not assumed from either
 * product's docs.
 * <p>
 * Also confirmed for real, and worth keeping in mind for any future model-name
 * change: dated aliases like {@code gemini-2.5-flash}/{@code gemini-1.5-flash}
 * returned 404 for this project's real key ("no longer available to new users" /
 * deprecated), even though {@code gemini-2.5-flash} is listed as a supported model
 * by the API's own {@code ListModels} response — only the {@code -latest} alias
 * (currently resolving to {@code gemini-3.6-flash}) worked. Availability by exact
 * dated model name for a given Google account is not reliable enough to hardcode.
 */
@Component
public class GeminiClient {

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/{model}:generateContent";

    private final RestClient restClient;
    private final String model;

    // A fresh RestClient.builder(), deliberately not the shared RestClient.Builder
    // bean ChatClientConfig exposes for Ollama/OpenAI's own autoconfiguration -
    // that bean is a singleton Spring auto-detects and reuses for those providers'
    // HTTP clients; calling .baseUrl()/.defaultHeader() on it here would risk
    // mutating shared state meant for local-model calls with a completely
    // different base URL and header, not something worth the coupling to save one
    // constructor line.
    public GeminiClient(FallbackProviderProperties properties) {
        this.model = properties.gemini().model();
        this.restClient = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader("x-goog-api-key", properties.gemini().apiKey())
                .build();
    }

    public String generateContent(String question) {
        GeminiResponse response = restClient.post()
                .uri(GENERATE_CONTENT_PATH, model)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new GeminiRequest(List.of(new GeminiRequest.Content(List.of(new GeminiRequest.Part(question))))))
                .retrieve()
                .body(GeminiResponse.class);
        return extractText(response);
    }

    private static String extractText(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new IllegalStateException("Gemini returned no candidates");
        }
        List<GeminiResponse.Part> parts = response.candidates().get(0).content().parts();
        if (parts == null || parts.isEmpty()) {
            throw new IllegalStateException("Gemini returned a candidate with no text parts");
        }
        return parts.get(0).text();
    }

    private record GeminiRequest(List<Content> contents) {
        private record Content(List<Part> parts) {
        }

        private record Part(String text) {
        }
    }

    /** Deliberately doesn't model {@code thoughtSignature} or other fields this project never reads. */
    private record GeminiResponse(List<Candidate> candidates) {
        private record Candidate(Content content) {
        }

        private record Content(List<Part> parts) {
        }

        private record Part(String text) {
        }
    }
}
