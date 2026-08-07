package com.eniglio.ragplatform.rag.exception;

/**
 * docs/PRODUCT-DIFFERENTIATION-ROADMAP.md Phase 8: thrown when the model's FAQ
 * response couldn't be parsed into at least one question/answer pair - a real
 * generation failure, not a client input error, so this fails loudly instead of
 * silently returning an empty FAQ list that would look like a (wrong) success.
 */
public class FaqGenerationException extends RuntimeException {

    public FaqGenerationException(String message) {
        super(message);
    }
}
