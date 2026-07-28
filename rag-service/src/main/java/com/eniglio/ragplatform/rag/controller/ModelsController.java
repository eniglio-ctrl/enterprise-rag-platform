package com.eniglio.ragplatform.rag.controller;

import com.eniglio.ragplatform.rag.config.RagProperties;
import com.eniglio.ragplatform.rag.dto.ModelOption;
import com.eniglio.ragplatform.rag.dto.ModelsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Backs the web-ui model dropdown (ADR 0017) — the list itself lives in
 * {@code rag.available-models} config, this just exposes it so the frontend never
 * hardcodes model names. The first configured entry is the default (matches
 * {@link com.eniglio.ragplatform.rag.service.RagQueryService#resolveModel}).
 */
@RestController
@Tag(name = "Models", description = "Chat models selectable per request")
public class ModelsController {

    private final RagProperties ragProperties;

    public ModelsController(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Operation(summary = "List selectable chat models",
            description = "Ollama entries beyond the default must already be pulled ('ollama pull <id>'); "
                    + "the LM Studio entry requires its local server to be running with a model loaded")
    @GetMapping("/api/v1/models")
    public ModelsResponse models() {
        List<RagProperties.AvailableModel> available = ragProperties.availableModels();
        List<ModelOption> options = IntStream.range(0, available.size())
                .mapToObj(i -> {
                    RagProperties.AvailableModel m = available.get(i);
                    return new ModelOption(m.id(), m.label(), m.provider(), i == 0);
                })
                .toList();
        return new ModelsResponse(options);
    }
}
