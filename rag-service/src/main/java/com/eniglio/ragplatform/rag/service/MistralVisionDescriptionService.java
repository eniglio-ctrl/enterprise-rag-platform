package com.eniglio.ragplatform.rag.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mistralai.MistralAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * Describes an attached image using Mistral AI's vision-capable Pixtral model — the
 * public demo's answer to {@link VisionDescriptionService}. Groq (the demo's text
 * chat provider, ADR 0020) has no vision model wired into this app, and Ollama isn't
 * reachable from the deployed demo at all, so this one capability needs its own
 * provider there. Mistral is already the demo's embedding provider (a second,
 * unrelated reason to have its API key configured) — reused here rather than adding
 * a fourth external account just for this.
 * <p>
 * Not routed through a Resilience4j gateway (unlike {@link OllamaVisionDescriptionService}):
 * this is the only Mistral chat call in the app, and adding a dedicated circuit
 * breaker/retry instance for a single, demo-only, best-effort capability wasn't
 * judged worth the extra config — a genuine, revisitable trade-off, not an oversight.
 */
@Component
@Profile("demo")
public class MistralVisionDescriptionService implements VisionDescriptionService {

    private static final String PROMPT = """
            Descreva esta imagem em detalhes para ajudar a responder a pergunta do usuário sobre ela.
            Inclua todo texto visível, transcrito literalmente, e os componentes, formas, ícones e a
            disposição/relação entre eles, se for um diagrama, gráfico ou captura de tela técnica.
            """;

    private final ChatClient mistralVisionChatClient;
    private final String visionModel;

    public MistralVisionDescriptionService(@Qualifier("mistralVision") ChatClient mistralVisionChatClient,
                                            @Value("${rag.vision.mistral-model:pixtral-12b-2409}") String visionModel) {
        this.mistralVisionChatClient = mistralVisionChatClient;
        this.visionModel = visionModel;
    }

    @Override
    public String describe(byte[] imageBytes, MimeType mimeType) {
        ByteArrayResource resource = new ByteArrayResource(imageBytes);
        return mistralVisionChatClient.prompt()
                .user(spec -> spec.text(PROMPT).media(mimeType, resource))
                .options(MistralAiChatOptions.builder().model(visionModel).build())
                .call()
                .content();
    }
}
