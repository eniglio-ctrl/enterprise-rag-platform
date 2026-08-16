package com.eniglio.ragplatform.rag.tool;

import com.eniglio.ragplatform.rag.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Multi-LLM Phase 9: the model can call this mid-answer to fetch a whole document by
 * its exact filename, when the question is about one specific already-known document
 * whose content might not have surfaced in the top-K similarity-ranked chunks (e.g.
 * "summarize document X" — a summary needs the whole document, not just the pieces
 * that happen to look similar to the word "summarize").
 * <p>
 * {@code tenantId}/{@code userId} are bound from {@link ToolContext}, supplied
 * server-side by {@code RagQueryService} when it registers this tool for a call -
 * never model-controlled parameters. The model choosing which tenant to query would
 * defeat the entire per-tenant isolation contract (ADR 0007) this project enforces
 * everywhere else; the tool's own {@code @Tool}-visible signature only exposes
 * {@code source}. docs/ROADMAP.md item #24: {@code userId} additionally lets {@link
 * HybridSearchService#findBySource} apply the same per-document ABAC check a normal
 * question is already subject to - without it, this tool would have been a way to
 * read a restricted document's content the model was never supposed to have access
 * to, bypassing the exact restriction {@link #lookupDocumentBySource} would
 * otherwise respect. docs/adr/0059-department-based-sharing.md, docs/adr/0060-multi
 * -department-membership-and-approval.md: {@code departments} rides along in
 * {@link ToolContext} the same way, absent entirely when the caller belongs to none
 * (see {@code RagQueryService.doAnswer}'s own tool-context construction).
 */
@Component
public class DocumentLookupTool {

    private static final Logger log = LoggerFactory.getLogger(DocumentLookupTool.class);

    private final HybridSearchService hybridSearchService;

    public DocumentLookupTool(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    @Tool(description = "Retorna o conteúdo completo de um documento já indexado, dado o nome exato do "
            + "arquivo (ex: 'saga-pattern.txt'). Use quando a pergunta pedir um resumo ou visão geral de um "
            + "documento específico mencionado pelo nome, e o conteúdo relevante não estiver no contexto já "
            + "fornecido.")
    public String lookupDocumentBySource(
            @ToolParam(description = "Nome exato do arquivo/fonte do documento, como aparece nas citações "
                    + "(ex: 'saga-pattern.txt')") String source,
            ToolContext toolContext) {
        String tenantId = (String) toolContext.getContext().get("tenantId");
        String userId = (String) toolContext.getContext().get("userId");
        @SuppressWarnings("unchecked")
        List<String> departments = (List<String>) toolContext.getContext().getOrDefault("departments", List.of());
        List<Document> chunks = hybridSearchService.findBySource(source, tenantId, userId, departments);
        log.info("Tool lookupDocumentBySource invoked: source={} tenantId={} chunksFound={}",
                source, tenantId, chunks.size());
        if (chunks.isEmpty()) {
            return "Nenhum documento encontrado com o nome '" + source + "'.";
        }
        // Already ordered by chunkIndex - HybridSearchService.findBySource's own SQL.
        return chunks.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
    }
}
