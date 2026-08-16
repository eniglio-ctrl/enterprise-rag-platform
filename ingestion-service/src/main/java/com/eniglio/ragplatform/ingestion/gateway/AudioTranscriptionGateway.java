package com.eniglio.ragplatform.ingestion.gateway;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Calls the local Whisper ASR server (ADR 0019, {@code onerahmet/openai-whisper-asr-webservice})
 * to transcribe an uploaded audio file — {@code POST /asr?output=txt} with the audio
 * as multipart field {@code audio_file}, returning the plain-text transcript as the
 * response body. Contract confirmed against the real container before writing this
 * (a spoken test .wav round-tripped through it correctly), not assumed from
 * documentation alone.
 * <p>
 * Its own Resilience4j instance ({@code "whisper"}, separate from {@code "ollama"}):
 * a genuinely different dependency in a different failure domain — Whisper being
 * down says nothing about Ollama's health, and vice versa.
 */
@Component
public class AudioTranscriptionGateway {

    private final RestClient restClient;

    public AudioTranscriptionGateway(
            @Value("${ingestion.whisper.base-url:http://localhost:9000}") String baseUrl,
            @Value("${ingestion.whisper.connect-timeout:5s}") Duration connectTimeout,
            @Value("${ingestion.whisper.read-timeout:120s}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        // Pinned to the JDK-HttpURLConnection-based factory, not detect(): the
        // JDK HttpClient-based factory that detect() otherwise picks sends an
        // "Upgrade: h2c" cleartext-HTTP/2 attempt alongside the chunked multipart
        // body, which the whisper container's uvicorn/Starlette server mishandles —
        // it silently drops the audio_file part, producing a 422 "field required"
        // even though the identical bytes work when sent without the h2c attempt.
        // Confirmed by capturing and replaying the exact raw bytes both ways
        // against the real container, not assumed.
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }

    @CircuitBreaker(name = "whisper")
    @Retry(name = "whisper")
    @Bulkhead(name = "whisper")
    public String transcribe(byte[] audioBytes, String filename) {
        // filename()/contentType() set explicitly on the part builder, not left to a
        // Resource subclass overriding getFilename(): relying on the Resource alone
        // still produced a part FastAPI's multipart parser didn't recognize as a real
        // file (422 "field required" for audio_file even though a value was present),
        // confirmed against the real running container, not assumed.
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("audio_file", new ByteArrayResource(audioBytes))
                .filename(filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM);

        return restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/asr").queryParam("output", "txt").build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .body(String.class);
    }
}
