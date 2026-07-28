package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.gateway.AudioTranscriptionGateway;
import org.springframework.stereotype.Component;

/**
 * Turns an uploaded audio file into text so it can flow through the same
 * chunk/embed/store pipeline as any other document (ADR 0019) — the audio itself is
 * never stored or embedded, only the transcript a local Whisper server produces from
 * it.
 */
@Component
public class AudioTranscriptionService {

    private final AudioTranscriptionGateway audioTranscriptionGateway;

    public AudioTranscriptionService(AudioTranscriptionGateway audioTranscriptionGateway) {
        this.audioTranscriptionGateway = audioTranscriptionGateway;
    }

    public String transcribe(byte[] audioBytes, String filename) {
        return audioTranscriptionGateway.transcribe(audioBytes, filename);
    }
}
