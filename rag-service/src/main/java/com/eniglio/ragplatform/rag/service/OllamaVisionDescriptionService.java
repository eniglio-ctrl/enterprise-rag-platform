package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.gateway.LlmGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * Describes an attached image using Ollama's vision-capable model (default
 * {@code llava}, same {@code VISION_MODEL} env var ingestion-service already uses
 * for image documents, ADR 0018) — the local/default profile's answer to
 * {@link VisionDescriptionService}. Explicitly overrides the model per call via
 * {@link OllamaOptions}: the {@code ollama} {@link ChatClient} bean's own configured
 * default model is the text chat model ({@code CHAT_MODEL}, e.g. llama3.1), not a
 * vision-capable one, so reusing it unmodified would send the image to the wrong
 * model entirely.
 * <p>
 * Routed through {@link LlmGateway#callOllama}, the same Resilience4j instance the
 * text-chat calls use — an Ollama outage is one failure domain regardless of which
 * capability is being called (identical reasoning to ingestion-service's
 * {@code VisionGateway} sharing the "ollama" instance with its embedding calls).
 */
@Component
@Profile("!demo")
public class OllamaVisionDescriptionService implements VisionDescriptionService {

    private static final String PROMPT = """
            Descreva esta imagem em detalhes para ajudar a responder a pergunta do usuário sobre ela.
            Inclua todo texto visível, transcrito literalmente, e os componentes, formas, ícones e a
            disposição/relação entre eles, se for um diagrama, gráfico ou captura de tela técnica.
            """;

    private final ChatClient ollamaChatClient;
    private final LlmGateway llmGateway;
    private final String visionModel;

    public OllamaVisionDescriptionService(@Qualifier("ollama") ChatClient ollamaChatClient,
                                           LlmGateway llmGateway,
                                           @Value("${rag.vision.ollama-model:llava}") String visionModel) {
        this.ollamaChatClient = ollamaChatClient;
        this.llmGateway = llmGateway;
        this.visionModel = visionModel;
    }

    @Override
    public String describe(byte[] imageBytes, MimeType mimeType) {
        ByteArrayResource resource = new ByteArrayResource(imageBytes);
        return llmGateway.callOllama(() -> ollamaChatClient.prompt()
                .user(spec -> spec.text(PROMPT).media(mimeType, resource))
                .options(OllamaOptions.builder().model(visionModel).build())
                .call()
                .content());
    }
}
