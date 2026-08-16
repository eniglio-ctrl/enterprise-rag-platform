package com.eniglio.ragplatform.chat.service;

import com.eniglio.ragplatform.chat.dto.MessageDto;
import com.eniglio.ragplatform.chat.dto.SendMessageResponse;
import com.eniglio.ragplatform.chat.exception.ConversationNotFoundException;
import com.eniglio.ragplatform.chat.gateway.LlmGateway;
import com.eniglio.ragplatform.chat.gateway.RagServiceGateway;
import com.eniglio.ragplatform.chat.repository.ConversationRepository;
import com.eniglio.ragplatform.common.web.Citation;
import com.eniglio.ragplatform.common.web.RetrievedChunk;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Multi-turn conversations on top of rag-service's retrieval (ADR 0013). Retrieval
 * itself is never reimplemented here — every question is sent to rag-service's
 * {@code /api/v1/retrieve} for the relevant chunks; this service only adds
 * conversation memory (via {@link MessageChatMemoryAdvisor}) around generating the
 * final, conversation-aware answer.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private static final int SNIPPET_LENGTH = 200;

    private static final String SYSTEM_TEMPLATE = """
            Você é um assistente técnico que responde exclusivamente com base no CONTEXTO
            abaixo, levando em conta o histórico da conversa.
            Regras:
            - Se a resposta não estiver no contexto, diga claramente que não encontrou informação suficiente.
            - Sempre cite as fontes usando os números entre colchetes que aparecem no contexto, ex: [1], [2].
            - Seja direto, técnico e responda no mesmo idioma da pergunta.

            CONTEXTO:
            {context}
            """;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final RagServiceGateway ragServiceGateway;
    private final LlmGateway llmGateway;
    private final ConversationRepository conversationRepository;
    private final Counter messagesExchangedCounter;
    private final Timer messageTimer;

    public ConversationService(ChatClient chatClient, ChatMemory chatMemory, RagServiceGateway ragServiceGateway,
                                LlmGateway llmGateway, ConversationRepository conversationRepository,
                                MeterRegistry meterRegistry) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.ragServiceGateway = ragServiceGateway;
        this.llmGateway = llmGateway;
        this.conversationRepository = conversationRepository;
        this.messagesExchangedCounter = Counter.builder("chat.messages.exchanged")
                .description("Number of chat messages answered")
                .register(meterRegistry);
        this.messageTimer = Timer.builder("chat.message.duration")
                .description("Time to answer a chat message, from retrieval to generated answer")
                .register(meterRegistry);
    }

    public String createConversation(String tenantId, String userId) {
        return conversationRepository.create(tenantId, userId);
    }

    public SendMessageResponse sendMessage(String conversationId, String tenantId, String message, String bearerToken) {
        SendMessageResponse response = messageTimer.record(
                () -> doSendMessage(conversationId, tenantId, message, bearerToken));
        messagesExchangedCounter.increment();
        return response;
    }

    private SendMessageResponse doSendMessage(String conversationId, String tenantId, String message,
            String bearerToken) {
        requireOwnership(conversationId, tenantId);

        // Forwards the caller's own token (ADR 0016) rather than re-deriving tenantId
        // for the outbound call — rag-service validates it itself against the same
        // JWKS and extracts tenantId from it, so it never needs to trust this service's
        // say-so about who's asking.
        List<RetrievedChunk> chunks = ragServiceGateway.retrieve(message, bearerToken);
        String context = buildContext(chunks);

        String answer = llmGateway.callOllama(() -> chatClient.prompt()
                .system(spec -> spec.text(SYSTEM_TEMPLATE).param("context", context))
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(conversationId).build())
                .user(message)
                .call()
                .content());

        log.info("Answered message in conversation {} using {} retrieved chunks", conversationId, chunks.size());

        return new SendMessageResponse(answer, chunks.stream().map(this::toCitation).toList());
    }

    public List<MessageDto> getMessages(String conversationId, String tenantId) {
        requireOwnership(conversationId, tenantId);
        return chatMemory.get(conversationId).stream()
                .map(m -> new MessageDto(m.getMessageType().name(), m.getText()))
                .toList();
    }

    private void requireOwnership(String conversationId, String tenantId) {
        if (!conversationRepository.belongsToTenant(conversationId, tenantId)) {
            throw new ConversationNotFoundException(conversationId);
        }
    }

    private String buildContext(List<RetrievedChunk> chunks) {
        return IntStream.range(0, chunks.size())
                .mapToObj(i -> "[%d] %s".formatted(i + 1, chunks.get(i).content()))
                .collect(Collectors.joining("\n\n"));
    }

    private Citation toCitation(RetrievedChunk chunk) {
        String content = chunk.content();
        String snippet = content == null || content.length() <= SNIPPET_LENGTH
                ? content
                : content.substring(0, SNIPPET_LENGTH) + "...";
        return new Citation(chunk.source(), chunk.chunkIndex(), chunk.score(), snippet);
    }
}
