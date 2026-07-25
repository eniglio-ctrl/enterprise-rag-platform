package com.eniglio.ragplatform.rag.dto;

import java.util.List;

/**
 * {@code mermaid} is the raw Mermaid.js flowchart definition (e.g. "flowchart LR\n...")
 * extracted from the retrieved context, ready to be rendered client-side. A blank
 * {@code mermaid} means no architecture/process description was found in the context.
 */
public record DiagramResponse(String mermaid, List<Citation> citations) {
}
