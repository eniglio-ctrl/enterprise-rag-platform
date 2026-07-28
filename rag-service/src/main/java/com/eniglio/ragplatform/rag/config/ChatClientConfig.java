package com.eniglio.ragplatform.rag.config;

import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Two {@code ChatClient} beans, not one (ADR 0017): with both
 * {@code spring-ai-starter-model-ollama} and {@code spring-ai-starter-model-openai}
 * on the classpath (the latter added so LM Studio's OpenAI-compatible local server is
 * selectable too), Spring AI's own auto-configured {@code ChatClient.Builder} backs
 * off entirely once more than one {@code ChatModel} bean exists — it only activates
 * for a single unambiguous candidate. Building each {@code ChatClient} explicitly
 * sidesteps that ambiguity without needing {@code spring.autoconfigure.exclude} to
 * pick just one provider, since both need to stay selectable at request time, not
 * decided once at startup. Depending on {@code ChatModel} by bean name
 * ("ollamaChatModel"/"openAiChatModel", Spring AI's own autoconfigured bean names)
 * rather than by concrete type also keeps {@code @MockitoBean(name = "...")} able to
 * substitute either one in tests — a concrete-type dependency here can't be replaced
 * by a mock that only implements the {@code ChatModel} interface.
 */
@Configuration
public class ChatClientConfig {

    @Bean
    @Qualifier("ollama")
    public ChatClient ollamaChatClient(@Qualifier("ollamaChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    @Qualifier("lmstudio")
    public ChatClient lmStudioChatClient(@Qualifier("openAiChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    // Neither Ollama nor a local LM Studio server has a built-in call timeout: a
    // stuck/overloaded model would hang the request indefinitely. Read timeout must
    // stay comfortably above real (slow, CPU-only) inference latency — a genuine
    // diagram/chat completion has taken up to ~2min in practice, so 90s cut off
    // legitimate responses, not just hangs. Spring Boot auto-detects this
    // RestClient.Builder bean as the base client for both providers' API clients —
    // sharing one timeout config is a deliberate simplification, both are local,
    // CPU-bound inference with similar latency characteristics.
    // Pinned to .simple() rather than .detect(): the JDK HttpClient-based factory
    // detect() otherwise selects sends an "Upgrade: h2c" cleartext-HTTP/2 attempt
    // alongside a chunked request body, which both Ollama's own Go HTTP server and
    // (in the ADR 0019 whisper case) uvicorn/Starlette have been confirmed — by
    // capturing and replaying the exact raw request bytes both ways — to sometimes
    // mishandle, corrupting or dropping the body (an image silently missing from a
    // vision call, or a 500 "unexpected EOF"). .simple() sends the identical bytes
    // without the upgrade attempt and was verified reliable in its place.
    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${rag.ollama.connect-timeout:5s}") Duration connectTimeout,
            @Value("${rag.ollama.read-timeout:180s}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings));
    }
}
