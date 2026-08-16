package com.eniglio.ragplatform.rag.dto;

import com.eniglio.ragplatform.common.web.Citation;

import java.util.List;

/**
 * {@code mermaid} is the raw Mermaid.js flowchart definition (e.g. "flowchart LR\n...")
 * extracted from the retrieved context, ready to be rendered client-side. A blank
 * {@code mermaid} means no architecture/process description was found in the context.
 * {@code model} is the chat model that generated it (ADR 0017), {@code null} when no
 * generation call was made (empty retrieval).
 */
public record DiagramResponse(String mermaid, List<Citation> citations, String model) {
}
