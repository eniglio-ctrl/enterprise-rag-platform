package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.config.RagProperties;
import com.eniglio.ragplatform.rag.dto.AskResponse;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.dto.DiagramResponse;
import com.eniglio.ragplatform.rag.dto.Groundedness;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RagQueryServiceTest {

    @Mock
    private HybridSearchService hybridSearchService;

    @Mock
    private LlmRerankService llmRerankService;

    @Mock
    private ChatModel chatModel;

    private RagQueryService newService() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        return new RagQueryService(hybridSearchService, llmRerankService, chatClient, new LlmGateway(),
                new RagProperties(5, 0.5, 15));
    }

    @Test
    void answersUsingRetrievedDocumentsAndCitesSources() {
        Document document = Document.builder()
                .text("SAGA coordena transações distribuídas via choreography ou orchestration.")
                .metadata(Map.of("source", "aula12.md", "chunkIndex", 3))
                .score(0.87)
                .build();

        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));

        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("O padrão SAGA é usado para transações distribuídas [1]"))));
        given(chatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", false, false);

        assertThat(response.answer()).contains("SAGA");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).source()).isEqualTo("aula12.md");
        assertThat(response.citations().get(0).chunkIndex()).isEqualTo(3);
        assertThat(response.groundedness()).isNull();
        verify(llmRerankService, never()).rerank(anyString(), any(), anyInt());
    }

    @Test
    void rerankRequestPassesHybridResultsThroughTheReranker() {
        Document original = Document.builder()
                .text("SAGA coordena transações distribuídas via choreography ou orchestration.")
                .metadata(Map.of("source", "aula12.md", "chunkIndex", 3))
                .score(0.5)
                .build();
        Document reranked = original.mutate().score(0.9).build();

        given(hybridSearchService.search("Como funciona o SAGA?", "default", 15)).willReturn(List.of(original));
        given(llmRerankService.rerank("Como funciona o SAGA?", List.of(original), 5)).willReturn(List.of(reranked));

        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("O padrão SAGA é usado para transações distribuídas [1]"))));
        given(chatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", false, true);

        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).score()).isEqualTo(0.9);
    }

    @Test
    void groundedAnswerIsMarkedSupportedWhenVerificationSaysSo() {
        Document document = Document.builder()
                .text("SAGA coordena transações distribuídas via choreography ou orchestration.")
                .metadata(Map.of("source", "aula12.md", "chunkIndex", 3))
                .score(0.87)
                .build();

        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String content = prompt.getSystemMessage().getText().contains("SUPORTADA")
                    ? "SUPORTADA"
                    : "O padrão SAGA é usado para transações distribuídas [1]";
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage(content))));
        });

        RagQueryService service = newService();
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", true, false);

        assertThat(response.groundedness()).isEqualTo(Groundedness.SUPPORTED);
    }

    @Test
    void groundedAnswerIsMarkedNotSupportedWhenVerificationSaysSo() {
        Document document = Document.builder()
                .text("SAGA coordena transações distribuídas via choreography ou orchestration.")
                .metadata(Map.of("source", "aula12.md", "chunkIndex", 3))
                .score(0.87)
                .build();

        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String content = prompt.getSystemMessage().getText().contains("SUPORTADA")
                    ? "NAO_SUPORTADA"
                    : "O padrão SAGA foi inventado no Brasil em 1990 [1]";
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage(content))));
        });

        RagQueryService service = newService();
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", true, false);

        assertThat(response.groundedness()).isEqualTo(Groundedness.NOT_SUPPORTED);
    }

    @Test
    void returnsFallbackMessageWhenNothingIsRetrieved() {
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());

        RagQueryService service = newService();
        ChatResponse response = service.answer("Pergunta sem contexto na base", "default", false, false);

        assertThat(response.citations()).isEmpty();
        assertThat(response.answer()).containsIgnoringCase("não encontrei informação suficiente");
    }

    @Test
    void extractsMermaidDiagramFromRetrievedDocuments() {
        Document document = Document.builder()
                .text("O cliente envia dados para o Amazon S3, que aciona uma AWS Lambda para processar o arquivo.")
                .metadata(Map.of("source", "aws-arquitetura.md", "chunkIndex", 0))
                .score(0.9)
                .build();

        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));

        String rawMermaid = "flowchart LR\n    A[Cliente] --> B[Amazon S3] --> C[AWS Lambda]";
        String expectedMermaid = "flowchart LR\n    A[\"Cliente\"] --> B[\"Amazon S3\"] --> C[\"AWS Lambda\"]";
        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("```mermaid\n" + rawMermaid + "\n```"))));
        given(chatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        DiagramResponse response = service.diagram("Desenhe a arquitetura descrita", "default");

        assertThat(response.mermaid()).isEqualTo(expectedMermaid);
        assertThat(response.citations()).hasSize(1);
    }

    @Test
    void quotesUnquotedLabelsContainingPunctuationThatWouldBreakMermaid() {
        Document document = Document.builder()
                .text("O banco de dados replica entre duas zonas de disponibilidade.")
                .metadata(Map.of("source", "aws-arquitetura.md", "chunkIndex", 0))
                .score(0.9)
                .build();

        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));

        String rawMermaid = "flowchart LR\n    A[Banco de Dados] --> B[Multi-AZ (alta disponibilidade)]";
        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(rawMermaid))));
        given(chatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        DiagramResponse response = service.diagram("Desenhe a arquitetura descrita", "default");

        assertThat(response.mermaid()).isEqualTo(
                "flowchart LR\n    A[\"Banco de Dados\"] --> B[\"Multi-AZ (alta disponibilidade)\"]");
    }

    @Test
    void fixesMalformedEdgeLabelsWithStrayAngleBracket() {
        Document document = Document.builder()
                .text("O ambiente de produção faz backup para o S3, que é restaurado na EC2.")
                .metadata(Map.of("source", "aws-arquitetura.md", "chunkIndex", 0))
                .score(0.9)
                .build();

        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));

        String rawMermaid = "flowchart LR\n    A[Producao] -->|Backup|> B[S3]";
        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(rawMermaid))));
        given(chatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        DiagramResponse response = service.diagram("Desenhe o fluxo descrito", "default");

        assertThat(response.mermaid()).isEqualTo(
                "flowchart LR\n    A[\"Producao\"] -->|Backup| B[\"S3\"]");
    }

    @Test
    void returnsEmptyDiagramWhenNothingIsRetrieved() {
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());

        RagQueryService service = newService();
        DiagramResponse response = service.diagram("Pergunta sem contexto na base", "default");

        assertThat(response.mermaid()).contains("Dados insuficientes");
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void askRoutesToDiagramWhenQuestionAsksForOne() {
        Document document = Document.builder()
                .text("O cliente envia dados para o Amazon S3, que aciona uma AWS Lambda.")
                .metadata(Map.of("source", "aws-arquitetura.md", "chunkIndex", 0))
                .score(0.9)
                .build();

        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));

        String rawMermaid = "flowchart LR\n    A[Cliente] --> B[Amazon S3]";
        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(rawMermaid))));
        given(chatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        AskResponse response = service.ask("Desenhe o fluxo descrito", "default", false, false);

        assertThat(response.type()).isEqualTo("diagram");
        assertThat(response.mermaid()).contains("Amazon S3");
        assertThat(response.answer()).isNull();
    }

    @Test
    void askRoutesToAnswerByDefault() {
        Document document = Document.builder()
                .text("SAGA coordena transações distribuídas via choreography ou orchestration.")
                .metadata(Map.of("source", "aula12.md", "chunkIndex", 3))
                .score(0.87)
                .build();

        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));

        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("O padrão SAGA é usado para transações distribuídas [1]"))));
        given(chatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        AskResponse response = service.ask("Como funciona o SAGA?", "default", false, false);

        assertThat(response.type()).isEqualTo("answer");
        assertThat(response.answer()).contains("SAGA");
        assertThat(response.mermaid()).isNull();
    }

    @Test
    void askRoutesToDiagramForAccentedGraficoKeyword() {
        Document document = Document.builder()
                .text("O cliente envia dados para o Amazon S3, que aciona uma AWS Lambda.")
                .metadata(Map.of("source", "aws-arquitetura.md", "chunkIndex", 0))
                .score(0.9)
                .build();

        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));

        String rawMermaid = "flowchart LR\n    A[Cliente] --> B[Amazon S3]";
        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(rawMermaid))));
        given(chatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        AskResponse response = service.ask("Faça um gráfico do funcionamento da AWS", "default", false, false);

        assertThat(response.type()).isEqualTo("diagram");
    }
}
