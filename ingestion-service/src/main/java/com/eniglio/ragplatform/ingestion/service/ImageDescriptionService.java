package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.gateway.VisionGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * Turns an uploaded image into text so it can flow through the same chunk/embed/store
 * pipeline as any other document (ADR 0018) — the image itself is never stored or
 * embedded directly, only the description a vision-capable Ollama model (default
 * {@code llava}, {@code VISION_MODEL} env var) produces from it.
 */
@Component
public class ImageDescriptionService {

    private static final String PROMPT = """
            Descreva esta imagem em detalhes para alguém que não pode vê-la. Inclua:
            - Todo texto visível na imagem, transcrito literalmente.
            - Componentes, formas, ícones e a disposição/relação entre eles, se for um
              diagrama, gráfico ou captura de tela técnica.
            - O contexto geral da cena, se for uma foto.
            Seja específico e completo — esta descrição substituirá a imagem para fins
            de busca, então informação omitida aqui fica irrecuperável depois.
            """;

    private final ChatClient chatClient;
    private final VisionGateway visionGateway;

    public ImageDescriptionService(ChatClient chatClient, VisionGateway visionGateway) {
        this.chatClient = chatClient;
        this.visionGateway = visionGateway;
    }

    public String describe(byte[] imageBytes, MimeType mimeType) {
        ByteArrayResource resource = new ByteArrayResource(imageBytes);
        return visionGateway.call(() -> chatClient.prompt()
                .user(spec -> spec.text(PROMPT).media(mimeType, resource))
                .call()
                .content());
    }
}
