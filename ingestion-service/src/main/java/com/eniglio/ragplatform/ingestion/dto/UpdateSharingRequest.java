package com.eniglio.ragplatform.ingestion.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * docs/ROADMAP.md item #24. {@code visibility} must be exactly {@code "TENANT"} or
 * {@code "RESTRICTED"} ({@link com.eniglio.ragplatform.common.authorization.DocumentVisibility})
 * — validated in {@code DocumentSharingService}, not here, since a Bean Validation
 * {@code @Pattern} would duplicate the same two literal strings a third place.
 * {@code sharedWith} is ignored when {@code visibility} is {@code "TENANT"} (nothing
 * to narrow); {@code null} is treated the same as an empty list.
 */
public record UpdateSharingRequest(@NotBlank String visibility, List<String> sharedWith) {
}
