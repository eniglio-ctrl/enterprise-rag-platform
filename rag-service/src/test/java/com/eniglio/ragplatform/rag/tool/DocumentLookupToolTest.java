package com.eniglio.ragplatform.rag.tool;

import com.eniglio.ragplatform.rag.service.HybridSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DocumentLookupToolTest {

    @Mock
    private HybridSearchService hybridSearchService;

    @Test
    void concatenatesEveryChunkOfTheFoundDocumentInOrder() {
        Document chunk0 = Document.builder().text("Primeira parte.").metadata(Map.of("chunkIndex", 0)).build();
        Document chunk1 = Document.builder().text("Segunda parte.").metadata(Map.of("chunkIndex", 1)).build();
        given(hybridSearchService.findBySource("saga-pattern.txt", "acme")).willReturn(List.of(chunk0, chunk1));

        String result = new DocumentLookupTool(hybridSearchService)
                .lookupDocumentBySource("saga-pattern.txt", new ToolContext(Map.of("tenantId", "acme")));

        assertThat(result).isEqualTo("Primeira parte.\n\nSegunda parte.");
    }

    @Test
    void tenantIdComesFromToolContextNeverFromTheModelSuppliedParameter() {
        given(hybridSearchService.findBySource(eq("saga-pattern.txt"), eq("acme"))).willReturn(List.of());

        new DocumentLookupTool(hybridSearchService)
                .lookupDocumentBySource("saga-pattern.txt", new ToolContext(Map.of("tenantId", "acme")));

        // The tool's own @Tool-visible signature only exposes "source" - this proves
        // tenantId genuinely comes from ToolContext, not something the model could
        // have smuggled in as part of the source string or otherwise influenced.
        verify(hybridSearchService).findBySource("saga-pattern.txt", "acme");
    }

    @Test
    void returnsAClearMessageWhenNoDocumentMatchesTheGivenSource() {
        given(hybridSearchService.findBySource("does-not-exist.txt", "acme")).willReturn(List.of());

        String result = new DocumentLookupTool(hybridSearchService)
                .lookupDocumentBySource("does-not-exist.txt", new ToolContext(Map.of("tenantId", "acme")));

        assertThat(result).contains("does-not-exist.txt");
    }
}
