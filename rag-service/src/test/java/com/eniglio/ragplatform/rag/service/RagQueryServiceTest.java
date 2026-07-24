package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RagQueryServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ChatModel chatModel;

    @Test
    void answersUsingRetrievedDocumentsAndCitesSources() {
        Document document = Document.builder()
                .text("SAGA coordena transações distribuídas via choreography ou orchestration.")
                .metadata(Map.of("source", "aula12.md", "chunkIndex", 3))
                .score(0.87)
                .build();

        given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of(document));

        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("O padrão SAGA é usado para transações distribuídas [1]"))));
        given(chatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        RagQueryService service = new RagQueryService(vectorStore, chatClient, new RagProperties(5, 0.5));

        com.eniglio.ragplatform.rag.dto.ChatResponse response = service.answer("Como funciona o SAGA?");

        assertThat(response.answer()).contains("SAGA");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).source()).isEqualTo("aula12.md");
        assertThat(response.citations().get(0).chunkIndex()).isEqualTo(3);
    }

    @Test
    void returnsFallbackMessageWhenNothingIsRetrieved() {
        given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of());

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        RagQueryService service = new RagQueryService(vectorStore, chatClient, new RagProperties(5, 0.5));

        com.eniglio.ragplatform.rag.dto.ChatResponse response = service.answer("Pergunta sem contexto na base");

        assertThat(response.citations()).isEmpty();
        assertThat(response.answer()).containsIgnoringCase("não encontrei informação suficiente");
    }
}
