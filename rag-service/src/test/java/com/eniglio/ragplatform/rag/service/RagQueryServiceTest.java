package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.config.RagProperties;
import com.eniglio.ragplatform.rag.dto.AskResponse;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.dto.DiagramResponse;
import com.eniglio.ragplatform.rag.dto.Groundedness;
import com.eniglio.ragplatform.rag.gateway.LlmGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private VisionDescriptionService visionDescriptionService;

    private RagQueryService newService() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        // Mirrors production config (ADR 0025): "auto" is always first, resolveModel
        // substitutes it for the first concrete entry ("ollama" here) — every existing
        // test below that never requests a model exercises that exact substitution
        // path, not a hypothetical one.
        List<RagProperties.AvailableModel> availableModels = List.of(
                new RagProperties.AvailableModel("auto", "Automático (recomendado)", "auto"),
                new RagProperties.AvailableModel("llama3.1", "Llama 3.1", "ollama"));
        // lmStudioChatClient is never exercised by these tests — every available model
        // is "ollama" (resolveModel always resolves to that provider), so the second
        // client param can be null without any test needing to touch it.
        return new RagQueryService(hybridSearchService, llmRerankService, chatClient, null, new LlmGateway(),
                new RagProperties(5, 0.5, 15, availableModels), visionDescriptionService, new SimpleMeterRegistry());
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
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", false, false, null);

        assertThat(response.answer()).contains("SAGA");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).source()).isEqualTo("aula12.md");
        assertThat(response.citations().get(0).chunkIndex()).isEqualTo(3);
        assertThat(response.groundedness()).isNull();
        verify(llmRerankService, never()).rerank(anyString(), any(), anyInt());
    }

    /**
     * ADR 0025: "auto" is a sentinel entry in rag.available-models, never a real
     * callable model — resolveModel must substitute it for the first concrete
     * (non-"auto") entry before any generation call, so the response never reports
     * back the literal string "auto" as the model that answered.
     */
    @Test
    void requestingAutoExplicitlyResolvesToTheFirstConcreteModel() {
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
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", false, false, "auto");

        assertThat(response.model()).isEqualTo("llama3.1");
    }

    @Test
    void requestingNoModelAlsoResolvesAutoToTheFirstConcreteModel() {
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
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", false, false, null);

        assertThat(response.model()).isEqualTo("llama3.1");
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
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", false, true, null);

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
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", true, false, null);

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
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", true, false, null);

        assertThat(response.groundedness()).isEqualTo(Groundedness.NOT_SUPPORTED);
    }

    @Test
    void returnsFallbackMessageWhenNothingIsRetrieved() {
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());

        RagQueryService service = newService();
        ChatResponse response = service.answer("Pergunta sem contexto na base", "default", false, false, null);

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
        DiagramResponse response = service.diagram("Desenhe a arquitetura descrita", "default", null);

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
        DiagramResponse response = service.diagram("Desenhe a arquitetura descrita", "default", null);

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
        DiagramResponse response = service.diagram("Desenhe o fluxo descrito", "default", null);

        assertThat(response.mermaid()).isEqualTo(
                "flowchart LR\n    A[\"Producao\"] -->|Backup| B[\"S3\"]");
    }

    @Test
    void returnsEmptyDiagramWhenNothingIsRetrieved() {
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());

        RagQueryService service = newService();
        DiagramResponse response = service.diagram("Pergunta sem contexto na base", "default", null);

        assertThat(response.mermaid()).contains("Dados insuficientes");
        assertThat(response.citations()).isEmpty();
    }

    /**
     * Routing is a real LLM classification call now (found broken as a fixed keyword
     * list by a real user report — see the regression test below), so every test
     * exercising {@code ask(...)} must mock two distinct calls: the routing
     * classification (system prompt contains the marker text below) and whatever
     * generation call follows. {@link #ROUTING_MARKER} matches
     * {@code RagQueryService.ROUTING_SYSTEM_TEMPLATE}'s distinguishing text.
     */
    private static final String ROUTING_MARKER = "Classifique a intenção";

    private static org.springframework.ai.chat.model.ChatResponse routeThenRespond(
            Prompt prompt, String routingVerdict, String otherwiseContent) {
        String content = prompt.getSystemMessage().getText().contains(ROUTING_MARKER)
                ? routingVerdict
                : otherwiseContent;
        return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(new AssistantMessage(content))));
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
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation ->
                routeThenRespond(invocation.getArgument(0), "DIAGRAMA", rawMermaid));

        RagQueryService service = newService();
        AskResponse response = service.ask("Desenhe o fluxo descrito", "default", false, false, null);

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

        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> routeThenRespond(invocation.getArgument(0),
                "RESPOSTA", "O padrão SAGA é usado para transações distribuídas [1]"));

        RagQueryService service = newService();
        AskResponse response = service.ask("Como funciona o SAGA?", "default", false, false, null);

        assertThat(response.type()).isEqualTo("answer");
        assertThat(response.answer()).contains("SAGA");
        assertThat(response.mermaid()).isNull();
    }

    @Test
    void askRoutesToDiagramWhenTheClassifierSaysSoEvenWithoutAnObviousKeyword() {
        Document document = Document.builder()
                .text("O cliente envia dados para o Amazon S3, que aciona uma AWS Lambda.")
                .metadata(Map.of("source", "aws-arquitetura.md", "chunkIndex", 0))
                .score(0.9)
                .build();

        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));

        String rawMermaid = "flowchart LR\n    A[Cliente] --> B[Amazon S3]";
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation ->
                routeThenRespond(invocation.getArgument(0), "DIAGRAMA", rawMermaid));

        RagQueryService service = newService();
        AskResponse response = service.ask("Faça um gráfico do funcionamento da AWS", "default", false, false, null);

        assertThat(response.type()).isEqualTo("diagram");
    }

    @Test
    void askWithAnAttachedImageAnswersEvenWithNoRetrievedChunks() {
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());
        given(visionDescriptionService.describe(any(byte[].class), any()))
                .willReturn("Um diagrama mostrando um cliente chamando uma API através de um API Gateway.");

        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> routeThenRespond(invocation.getArgument(0),
                "RESPOSTA", "A imagem mostra um API Gateway."));

        RagQueryService service = newService();
        byte[] imageBytes = {1, 2, 3};
        AskResponse response = service.ask("O que aparece no anexo enviado?", "default", false, false, null,
                imageBytes, org.springframework.util.MimeType.valueOf("image/png"));

        assertThat(response.type()).isEqualTo("answer");
        assertThat(response.answer()).contains("API Gateway");
        verify(visionDescriptionService).describe(eq(imageBytes), eq(org.springframework.util.MimeType.valueOf("image/png")));
    }

    /**
     * Regression test for a real bug a user hit: "imagem"/"picture" used to be in a
     * fixed DIAGRAM_KEYWORDS list, so "O que tem nessa imagem?" — the single most
     * natural question to ask about an attached photo — always misrouted to diagram
     * generation instead of using the vision description to answer normally. Routing
     * is now a real classification call (not a keyword list), so this asserts the
     * classifier itself is asked and its "RESPOSTA" verdict is honored — not that a
     * word happens to be missing from a list.
     */
    @Test
    void askingWhatIsInTheAttachedImageRoutesToAnswerNotDiagram() {
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());
        given(visionDescriptionService.describe(any(byte[].class), any()))
                .willReturn("Um quadrado vermelho centralizado em um fundo azul.");

        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> routeThenRespond(invocation.getArgument(0),
                "RESPOSTA", "A imagem mostra um quadrado vermelho sobre fundo azul."));

        RagQueryService service = newService();
        AskResponse response = service.ask("O que tem nessa imagem?", "default", false, false, null,
                new byte[]{1, 2, 3}, org.springframework.util.MimeType.valueOf("image/png"));

        assertThat(response.type()).isEqualTo("answer");
        assertThat(response.mermaid()).isNull();
        assertThat(response.answer()).contains("quadrado vermelho");
    }

    @Test
    void askWithAnAttachedImageFoldsItsDescriptionIntoTheSystemPromptAsANonNumberedBlock() {
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());
        given(visionDescriptionService.describe(any(byte[].class), any()))
                .willReturn("Uma captura de tela de um dashboard do Grafana.");

        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            if (prompt.getSystemMessage().getText().contains(ROUTING_MARKER)) {
                return routeThenRespond(prompt, "RESPOSTA", "RESPOSTA");
            }
            String systemText = prompt.getSystemMessage().getText();
            String content = systemText.contains("[IMAGEM]") && systemText.contains("dashboard do Grafana")
                    ? "Resposta usando a imagem."
                    : "Resposta sem a imagem — algo deu errado.";
            return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        });

        RagQueryService service = newService();
        AskResponse response = service.ask("O que aparece no anexo enviado?", "default", false, false, null,
                new byte[]{9, 9, 9}, org.springframework.util.MimeType.valueOf("image/png"));

        assertThat(response.answer()).isEqualTo("Resposta usando a imagem.");
    }

    @Test
    void askWithoutAnImageNeverCallsTheVisionDescriptionService() {
        Document document = Document.builder()
                .text("SAGA coordena transações distribuídas via choreography ou orchestration.")
                .metadata(Map.of("source", "aula12.md", "chunkIndex", 3))
                .score(0.87)
                .build();
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));
        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("O padrão SAGA [1]"))));
        given(chatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        service.ask("Como funciona o SAGA?", "default", false, false, null);

        verify(visionDescriptionService, never()).describe(any(), any());
    }
}
