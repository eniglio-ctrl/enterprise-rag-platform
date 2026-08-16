package com.eniglio.ragplatform.rag.exception;

/**
 * docs/PRODUCT-DIFFERENTIATION-ROADMAP.md Phase 8: thrown by the summarize/FAQ
 * endpoints when {@code documentId} matches no chunk visible to the caller — either
 * the document genuinely doesn't exist, or it does but ABAC (ADR 0046) filtered it
 * out. Deliberately indistinguishable between those two cases, same as every other
 * visibility check in this codebase.
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String message) {
        super(message);
    }
}
