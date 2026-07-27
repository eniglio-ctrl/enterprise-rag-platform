package com.eniglio.ragplatform.chat.service;

import com.eniglio.ragplatform.chat.dto.MessageDto;
import com.eniglio.ragplatform.chat.dto.SendMessageResponse;
import com.eniglio.ragplatform.chat.exception.ConversationNotFoundException;
import com.eniglio.ragplatform.chat.gateway.RagServiceGateway;
import com.eniglio.ragplatform.chat.repository.ConversationRepository;
import com.eniglio.ragplatform.common.web.RetrievedChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private RagServiceGateway ragServiceGateway;

    @Mock
    private ConversationRepository conversationRepository;

    private ConversationService newService() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        return new ConversationService(chatClient, chatMemory, ragServiceGateway, conversationRepository,
                new SimpleMeterRegistry());
    }

    @Test
    void createConversationDelegatesToRepository() {
        given(conversationRepository.create("tenant-a", "user-a")).willReturn("conv-1");

        String id = newService().createConversation("tenant-a", "user-a");

        assertThat(id).isEqualTo("conv-1");
    }

    @Test
    void sendMessageThrowsWhenConversationDoesNotBelongToTenant() {
        given(conversationRepository.belongsToTenant("conv-1", "tenant-a")).willReturn(false);

        ConversationService service = newService();

        assertThatThrownBy(() -> service.sendMessage("conv-1", "tenant-a", "oi", "token-abc"))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void sendMessageBuildsTruncatedCitationsFromFullRetrievedContent() {
        given(conversationRepository.belongsToTenant("conv-1", "tenant-a")).willReturn(true);
        given(chatMemory.get("conv-1")).willReturn(List.of());
        String longContent = "x".repeat(250);
        given(ragServiceGateway.retrieve("pergunta", "token-abc")).willReturn(
                List.of(new RetrievedChunk("aula.md", 0, 0.9, longContent)));
        given(chatModel.call(any(Prompt.class))).willReturn(
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("resposta [1]")))));

        SendMessageResponse response = newService().sendMessage("conv-1", "tenant-a", "pergunta", "token-abc");

        assertThat(response.answer()).isEqualTo("resposta [1]");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).source()).isEqualTo("aula.md");
        assertThat(response.citations().get(0).snippet()).hasSize(203);
        assertThat(response.citations().get(0).snippet()).endsWith("...");
    }

    @Test
    void getMessagesThrowsWhenConversationDoesNotBelongToTenant() {
        given(conversationRepository.belongsToTenant("conv-1", "tenant-a")).willReturn(false);

        ConversationService service = newService();

        assertThatThrownBy(() -> service.getMessages("conv-1", "tenant-a"))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void getMessagesMapsChatMemoryEntriesToDtos() {
        given(conversationRepository.belongsToTenant("conv-1", "tenant-a")).willReturn(true);
        given(chatMemory.get("conv-1")).willReturn(List.of(new UserMessage("oi"), new AssistantMessage("ola")));

        List<MessageDto> messages = newService().getMessages("conv-1", "tenant-a");

        assertThat(messages).extracting(MessageDto::role).containsExactly("USER", "ASSISTANT");
        assertThat(messages).extracting(MessageDto::content).containsExactly("oi", "ola");
    }
}
