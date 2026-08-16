package com.eniglio.ragplatform.rag.service;

import org.springframework.util.MimeType;

/**
 * Describes an image attached to a question, ephemerally — the description folds
 * into that single question's context, never gets indexed or persisted. Exactly one
 * implementation is active per Spring profile (see {@link OllamaVisionDescriptionService}/
 * {@link MistralVisionDescriptionService}): the local stack has Ollama's vision model
 * reachable but no Mistral chat model configured, the public demo has the opposite.
 */
public interface VisionDescriptionService {

    String describe(byte[] imageBytes, MimeType mimeType);
}
