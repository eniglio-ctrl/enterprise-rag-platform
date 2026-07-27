package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.gateway.LlmGateway;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LlmRerankServiceTest {

    @Mock
    private ChatModel chatModel;

    @Test
    void ordersAndTrimsCandidatesByLlmScore() {
        Document lowRelevance = Document.builder().id("0").text("baixa relevância").metadata(Map.of("source", "a")).build();
        Document highRelevance = Document.builder().id("1").text("altamente relevante").metadata(Map.of("source", "b")).build();

        String structuredJson = "{\"scores\":[{\"index\":0,\"score\":2},{\"index\":1,\"score\":9}]}";
        given(chatModel.call(any(Prompt.class))).willReturn(
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(structuredJson)))));

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        LlmRerankService service = new LlmRerankService(chatClient, new LlmGateway());

        List<Document> result = service.rerank("pergunta", List.of(lowRelevance, highRelevance), 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("1");
        assertThat(result.get(0).getScore()).isEqualTo(0.9);
    }

    @Test
    void missingIndexInModelResponseDefaultsToZeroScoreRatherThanFailing() {
        Document scored = Document.builder().id("0").text("t").metadata(Map.of()).build();
        Document unscored = Document.builder().id("1").text("t").metadata(Map.of()).build();

        String structuredJson = "{\"scores\":[{\"index\":0,\"score\":7}]}";
        given(chatModel.call(any(Prompt.class))).willReturn(
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(structuredJson)))));

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        LlmRerankService service = new LlmRerankService(chatClient, new LlmGateway());

        List<Document> result = service.rerank("pergunta", List.of(scored, unscored), 2);

        assertThat(result).extracting(Document::getId).containsExactly("0", "1");
    }

    @Test
    void fallsBackToPreRerankOrderWhenModelResponseHasNoScoresArray() {
        // Observed for real against llama3.1: structured output parses without
        // throwing, but the resulting object has no "scores" array at all.
        Document first = Document.builder().id("0").text("t").metadata(Map.of()).build();
        Document second = Document.builder().id("1").text("t").metadata(Map.of()).build();

        given(chatModel.call(any(Prompt.class))).willReturn(
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("{}")))));

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        LlmRerankService service = new LlmRerankService(chatClient, new LlmGateway());

        List<Document> result = service.rerank("pergunta", List.of(first, second), 2);

        assertThat(result).extracting(Document::getId).containsExactly("0", "1");
    }

    @Test
    void fallsBackToPreRerankOrderWhenTheModelCallItselfFails() {
        Document first = Document.builder().id("0").text("t").metadata(Map.of()).build();

        given(chatModel.call(any(Prompt.class))).willThrow(new RuntimeException("model unavailable"));

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        LlmRerankService service = new LlmRerankService(chatClient, new LlmGateway());

        List<Document> result = service.rerank("pergunta", List.of(first), 5);

        assertThat(result).extracting(Document::getId).containsExactly("0");
    }

    @Test
    void returnsEmptyListWithoutCallingTheModelWhenNoCandidates() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        LlmRerankService service = new LlmRerankService(chatClient, new LlmGateway());

        List<Document> result = service.rerank("pergunta", List.of(), 5);

        assertThat(result).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
    }
}
