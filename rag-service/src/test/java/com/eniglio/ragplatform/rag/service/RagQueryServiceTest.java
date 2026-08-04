package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.config.FallbackProviderProperties;
import com.eniglio.ragplatform.rag.config.RagProperties;
import com.eniglio.ragplatform.rag.dto.AskResponse;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.dto.ContextRelevance;
import com.eniglio.ragplatform.rag.dto.DiagramResponse;
import com.eniglio.ragplatform.rag.dto.Groundedness;
import com.eniglio.ragplatform.rag.gateway.GeminiClient;
import com.eniglio.ragplatform.rag.gateway.LlmGateway;
import com.eniglio.ragplatform.rag.tool.DocumentLookupTool;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private ChatModel openAiFallbackChatModel;

    @Mock
    private ChatModel anthropicFallbackChatModel;

    @Mock
    private GeminiClient geminiClient;

    @Mock
    private VisionDescriptionService visionDescriptionService;

    // Real, not mocked (Multi-LLM Phase 2b/2c) - shared with newService() below so
    // individual tests can drive real circuit-breaker state transitions the same way
    // FallbackTriggerEvaluatorTest does.
    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();

    private RagQueryService newService() {
        return newService(new FallbackProviderProperties(
                new FallbackProviderProperties.OpenAi("test-openai-key", "gpt-4o-mini"),
                new FallbackProviderProperties.Gemini("test-gemini-key", "gemini-flash-latest"),
                new FallbackProviderProperties.Anthropic("test-anthropic-key", "claude-haiku-4-5-20251001"),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30)));
    }

    private RagQueryService newService(FallbackProviderProperties fallbackProviderProperties) {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        // Multi-LLM Phase 2a's fallback ChatClient, wired the same way the local ones
        // are - only exercised by the Phase 2c fallback tests below.
        ChatClient openAiFallbackChatClient = ChatClient.builder(openAiFallbackChatModel).build();
        // Multi-LLM Phase 2e (ADR 0045) - same pattern as OpenAI's above.
        ChatClient anthropicFallbackChatClient = ChatClient.builder(anthropicFallbackChatModel).build();
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
        return new RagQueryService(hybridSearchService, llmRerankService, chatClient, null, openAiFallbackChatClient,
                anthropicFallbackChatClient,
                geminiClient, new LlmGateway(), new RagProperties(5, 0.5, 15, availableModels),
                fallbackProviderProperties, new FallbackTriggerEvaluator(circuitBreakerRegistry),
                visionDescriptionService, new DocumentLookupTool(hybridSearchService), new SimpleMeterRegistry());
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
        // Multi-LLM Phase 2c (ADR 0038): every normal, grounded answer marks its own
        // provenance explicitly - web-ui (Phase 2d) never has to infer it.
        assertThat(response.source()).isEqualTo("local");
        assertThat(response.fallbackAvailable()).isNull();
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

    /**
     * An unrecognized model id falls back to the first configured model instead of
     * erroring the whole question (existing behavior). The id here also carries a
     * newline, which the warning log strips before writing it (log injection,
     * CWE-117) — the log statement itself isn't asserted on, but a passing test
     * proves the sanitization line executes without throwing.
     */
    @Test
    void unknownModelIdFallsBackToTheFirstConcreteModel() {
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
        ChatResponse response = service.answer(
                "Como funciona o SAGA?", "default", false, false, "gpt-9-does-not-exist\nFAKE LOG LINE");

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
        // Multi-LLM Phase 2c (ADR 0038): empty retrieval is one of the two structural
        // fallback triggers - the caller must be told a public-LLM fallback exists,
        // without any LLM (local or public) having been called yet.
        assertThat(response.fallbackAvailable()).isTrue();
        assertThat(response.source()).isNull();
        verify(chatModel, never()).call(any(Prompt.class));
        verify(openAiFallbackChatModel, never()).call(any(Prompt.class));
        verify(geminiClient, never()).generateContent(anyString());
    }

    @Test
    void offersFallbackWithoutCallingTheLocalModelWhenItsCircuitBreakerIsOpen() {
        Document document = Document.builder()
                .text("SAGA coordena transações distribuídas.")
                .metadata(Map.of("source", "aula12.md", "chunkIndex", 3))
                .score(0.87)
                .build();
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of(document));
        circuitBreakerRegistry.circuitBreaker("ollama").transitionToOpenState();

        RagQueryService service = newService();
        ChatResponse response = service.answer("Como funciona o SAGA?", "default", false, false, null);

        assertThat(response.fallbackAvailable()).isTrue();
        assertThat(response.source()).isNull();
        // The whole point of Phase 2b's check running before generation: an open
        // breaker must skip the call entirely, not attempt it and let Resilience4j's
        // CallNotPermittedException propagate as an unhandled 500.
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void confirmedFallbackCallsGeminiByDefaultAndSendsOnlyTheRawQuestion() {
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());
        given(geminiClient.generateContent(anyString())).willReturn("Resposta pública, não fundamentada.");

        RagQueryService service = newService();
        ChatResponse response = service.answer("Pergunta sem contexto na base", "default", false, false, null,
                true, null);

        assertThat(response.answer()).isEqualTo("Resposta pública, não fundamentada.");
        assertThat(response.citations()).isEmpty();
        assertThat(response.groundedness()).isNull();
        assertThat(response.source()).isEqualTo("public-llm");
        assertThat(response.fallbackAvailable()).isNull();
        assertThat(response.model()).isEqualTo("gemini-flash-latest");

        ArgumentCaptor<String> sentQuestion = ArgumentCaptor.forClass(String.class);
        verify(geminiClient).generateContent(sentQuestion.capture());
        // The exact question, nothing appended - no retrieved chunk or document
        // content ever reaches the public API through this path (ADR 0038).
        assertThat(sentQuestion.getValue()).isEqualTo("Pergunta sem contexto na base");
        verify(chatModel, never()).call(any(Prompt.class));
        verify(openAiFallbackChatModel, never()).call(any(Prompt.class));
    }

    @Test
    void confirmedFallbackCallsOpenAiWhenExplicitlyRequested() {
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());
        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("Resposta da OpenAI."))));
        given(openAiFallbackChatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        ChatResponse response = service.answer("Pergunta sem contexto na base", "default", false, false, null,
                true, "openai");

        assertThat(response.answer()).isEqualTo("Resposta da OpenAI.");
        assertThat(response.source()).isEqualTo("public-llm");
        assertThat(response.model()).isEqualTo("gpt-4o-mini");
        verify(chatModel, never()).call(any(Prompt.class));
        verify(geminiClient, never()).generateContent(anyString());
    }

    @Test
    void confirmedFallbackCallsAnthropicWhenExplicitlyRequested() {
        // Multi-LLM Phase 2e (ADR 0045) - same shape as the OpenAI test above.
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());
        org.springframework.ai.chat.model.ChatResponse mockedChatResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("Resposta da Anthropic."))));
        given(anthropicFallbackChatModel.call(any(Prompt.class))).willReturn(mockedChatResponse);

        RagQueryService service = newService();
        ChatResponse response = service.answer("Pergunta sem contexto na base", "default", false, false, null,
                true, "anthropic");

        assertThat(response.answer()).isEqualTo("Resposta da Anthropic.");
        assertThat(response.source()).isEqualTo("public-llm");
        assertThat(response.model()).isEqualTo("claude-haiku-4-5-20251001");
        verify(chatModel, never()).call(any(Prompt.class));
        verify(geminiClient, never()).generateContent(anyString());
        verify(openAiFallbackChatModel, never()).call(any(Prompt.class));
    }

    @Test
    void confirmedFallbackSkipsTheCallAndAnswersGracefullyWhenTheProviderHasNoApiKeyConfigured() {
        // docs/ROADMAP.md item #12 / user request: a provider with no key configured
        // (Anthropic's own real, current state - no ANTHROPIC_API_KEY exists yet,
        // unlike OpenAI/Gemini) must never even attempt the call, and must answer
        // gracefully instead of failing the request.
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());
        FallbackProviderProperties noAnthropicKey = new FallbackProviderProperties(
                new FallbackProviderProperties.OpenAi("test-openai-key", "gpt-4o-mini"),
                new FallbackProviderProperties.Gemini("test-gemini-key", "gemini-flash-latest"),
                new FallbackProviderProperties.Anthropic("", "claude-haiku-4-5-20251001"),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30));

        RagQueryService service = newService(noAnthropicKey);
        ChatResponse response = service.answer("Pergunta sem contexto na base", "default", false, false, null,
                true, "anthropic");

        assertThat(response.source()).isEqualTo("public-llm-unavailable");
        assertThat(response.answer()).containsIgnoringCase("Anthropic");
        assertThat(response.citations()).isEmpty();
        verify(anthropicFallbackChatModel, never()).call(any(Prompt.class));
    }

    @Test
    void confirmedFallbackAnswersGracefullyWhenTheProviderRejectsTheRequest() {
        // The real, confirmed OpenAI state as of ADR 0036: the key authenticates but
        // the account has zero credits - a genuine 429/insufficient_quota-shaped
        // failure from the provider itself, not a wiring bug. Must not become a raw
        // 500; must answer gracefully instead, same as the no-key case above.
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());
        given(openAiFallbackChatModel.call(any(Prompt.class)))
                .willThrow(new RuntimeException("429 - {\"error\": {\"code\": \"insufficient_quota\"}}"));

        RagQueryService service = newService();
        ChatResponse response = service.answer("Pergunta sem contexto na base", "default", false, false, null,
                true, "openai");

        assertThat(response.source()).isEqualTo("public-llm-unavailable");
        assertThat(response.answer()).containsIgnoringCase("OpenAI");
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void confirmedFallbackReThrowsACircuitBreakerOpenSignalInsteadOfSwallowingIt() {
        // docs/ROADMAP.md item #17/#43: a genuine infrastructure signal (the
        // "anthropic-fallback" circuit already open) must still propagate exactly as
        // before this phase - GlobalExceptionHandlerSupport's existing, tested 503
        // handling for it must not be bypassed by the new graceful-answer path above.
        given(hybridSearchService.search(anyString(), anyString(), anyInt())).willReturn(List.of());
        given(anthropicFallbackChatModel.call(any(Prompt.class)))
                .willThrow(io.github.resilience4j.circuitbreaker.CallNotPermittedException.createCallNotPermittedException(
                        io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("anthropic-fallback")));

        RagQueryService service = newService();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.answer(
                        "Pergunta sem contexto na base", "default", false, false, null, true, "anthropic"))
                .isInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
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

    // --- Multi-LLM Phase 8: standalone faithfulness/context-relevance checks,
    // reused by RagQualityBenchmark outside the full answer()/ask() request cycle ---

    @Test
    void checkGroundednessIsReusableStandaloneOutsideTheAnswerFlow() {
        given(chatModel.call(any(Prompt.class))).willReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage("SUPORTADA")))));

        Groundedness result = newService().checkGroundedness("[1] SAGA coordena transações.",
                "O padrão SAGA coordena transações [1]");

        assertThat(result).isEqualTo(Groundedness.SUPPORTED);
    }

    @Test
    void checkContextRelevanceMarksAChunkThatAnswersTheQuestionAsRelevant() {
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String content = prompt.getSystemMessage().getText().contains("RELEVANTE")
                    ? "RELEVANTE"
                    : "resposta genérica";
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage(content))));
        });

        ContextRelevance result = newService().checkContextRelevance("Como funciona o SAGA?",
                "SAGA coordena transações distribuídas via choreography ou orchestration.");

        assertThat(result).isEqualTo(ContextRelevance.RELEVANT);
    }

    @Test
    void checkContextRelevanceMarksAnUnrelatedChunkAsNotRelevant() {
        // "IRRELEVANTE" contains "RELEVANTE" as a substring - this is the regression
        // test for parseContextRelevance checking the negative token first, the same
        // pitfall parseGroundedness already handles for NAO_SUPORTADA/SUPORTADA.
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String content = prompt.getSystemMessage().getText().contains("RELEVANTE")
                    ? "IRRELEVANTE"
                    : "resposta genérica";
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage(content))));
        });

        ContextRelevance result = newService().checkContextRelevance("Como funciona o SAGA?",
                "A receita de bolo de cenoura leva três ovos.");

        assertThat(result).isEqualTo(ContextRelevance.NOT_RELEVANT);
    }
}
