package com.eniglio.ragplatform.chat.integration;

import com.eniglio.ragplatform.chat.ChatServiceApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses a plain {@code postgres:16} container (no vector extension needed — chat-service
 * never touches pgvector) and a real, minimal JDK {@link HttpServer} standing in for
 * rag-service, rather than mocking {@code RagServiceGateway} itself: this exercises the
 * actual HTTP call, including the {@code Authorization} bearer-token forwarding
 * (ADR 0016) and JSON (de)serialization, not just chat-service's own logic in isolation
 * (that's what {@code ConversationServiceTest} already covers).
 */
@Testcontainers
@SpringBootTest(classes = ChatServiceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationIT {

    private static final String TEST_TOKEN_VALUE = "test-jwt-token";

    private static volatile String lastAuthorizationHeaderSeenByRagServiceStub;

    private static final HttpServer RAG_SERVICE_STUB = startRagServiceStub();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
            .withDatabaseName("ragplatform")
            .withUsername("ragplatform")
            .withPassword("ragplatform");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // getJdbcUrl() already includes "?loggerLevel=OFF" — a second "?" here would
        // silently produce an invalid URL that drops currentSchema entirely instead
        // of erroring (confirmed: search_path stayed at the connection default and
        // every query against the `chat` schema failed with "relation does not exist").
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=chat");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("chat.rag-service.base-url",
                () -> "http://localhost:" + RAG_SERVICE_STUB.getAddress().getPort());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void stubChatModel() {
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation ->
                new ChatResponse(List.of(new Generation(new AssistantMessage("Resposta sobre SAGA [1]")))));
    }

    @Test
    void createsConversationAndSendsMessageUsingRetrievedContext() throws Exception {
        String conversationId = createConversation();

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .with(testJwt())
                        .contentType("application/json")
                        .content("{\"message\":\"Como funciona o SAGA?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(containsString("SAGA")))
                .andExpect(jsonPath("$.citations[0].source").value("aula12.md"));

        // Verifies the actual wiring (ADR 0016), not just that chat-service itself
        // accepted the caller's token: the same token must reach rag-service too,
        // forwarded as-is by RagServiceGateway rather than re-derived or dropped.
        assertThat(lastAuthorizationHeaderSeenByRagServiceStub).isEqualTo("Bearer " + TEST_TOKEN_VALUE);
    }

    @Test
    void unknownConversationReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/" + java.util.UUID.randomUUID() + "/messages")
                        .with(testJwt())
                        .contentType("application/json")
                        .content("{\"message\":\"oi\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listingMessagesForAnUnknownConversationReturnsNotFound() throws Exception {
        // Real gap this phase's own scope called out to close (docs/ROADMAP.md #15):
        // unknownConversationReturnsNotFound above only ever exercised the POST
        // messages endpoint, never GET - a caller who never sent a message but tries
        // to list one deserves the same 404, not a silent empty list.
        mockMvc.perform(get("/api/v1/conversations/" + java.util.UUID.randomUUID() + "/messages").with(testJwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendingABlankMessageReturnsBadRequest() throws Exception {
        // SendMessageRequest's @NotBlank was never exercised by a real HTTP request
        // before this - a validation annotation with no test proving it's actually
        // enforced at the controller layer isn't verified, it's just typed.
        String conversationId = createConversation();

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .with(testJwt())
                        .contentType("application/json")
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendingAMessageLongerThanTheSizeLimitReturnsBadRequest() throws Exception {
        String conversationId = createConversation();
        String tooLong = "a".repeat(8001);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .with(testJwt())
                        .contentType("application/json")
                        .content("{\"message\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sixthMessageEvictsTheOldestTurnOnceTheWindowIsExceeded() throws Exception {
        String conversationId = createConversation();

        for (int i = 1; i <= 6; i++) {
            String question = "Pergunta numero " + i;
            given(chatModel.call(any(Prompt.class))).willAnswer(invocation ->
                    new ChatResponse(List.of(new Generation(new AssistantMessage("Resposta " + question)))));

            mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                            .with(testJwt())
                            .contentType("application/json")
                            .content("{\"message\":\"" + question + "\"}"))
                    .andExpect(status().isOk());
        }

        // chat.max-messages=10 (5 turns worth): the 6th turn pushes the 1st out.
        MvcResult result = mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages").with(testJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Pergunta numero 1\"");
        assertThat(body).contains("Pergunta numero 6");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor testJwt() {
        return jwt().jwt(token -> token.tokenValue(TEST_TOKEN_VALUE)
                .subject("user-1")
                .claim("tenantId", "default"));
    }

    private String createConversation() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/conversations").with(testJwt()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("conversationId").asText();
    }

    private static HttpServer startRagServiceStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/api/v1/retrieve", ConversationIT::handleRetrieve);
            server.start();
            return server;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void handleRetrieve(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        lastAuthorizationHeaderSeenByRagServiceStub = exchange.getRequestHeaders().getFirst("Authorization");

        String body = """
                {"chunks":[{"source":"aula12.md","chunkIndex":0,"score":0.87,"content":"O padrão SAGA coordena transações distribuídas usando choreography ou orchestration."}]}
                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
