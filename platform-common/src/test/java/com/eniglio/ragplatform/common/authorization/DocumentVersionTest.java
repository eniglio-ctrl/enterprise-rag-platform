package com.eniglio.ragplatform.common.authorization;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentVersionTest {

    @Test
    void aDocumentWithNoVersioningMetadataIsTreatedAsTheLatestVersion() {
        // Every chunk ingested before docs/adr/0058-document-versioning.md existed
        // has no "isLatestVersion" key at all.
        assertThat(DocumentVersion.isLatestVersion(Map.of())).isTrue();
    }

    @Test
    void aDocumentExplicitlyMarkedAsTheLatestVersionIsTheLatestVersion() {
        assertThat(DocumentVersion.isLatestVersion(Map.of("isLatestVersion", true))).isTrue();
    }

    @Test
    void aSupersededDocumentIsNotTheLatestVersion() {
        assertThat(DocumentVersion.isLatestVersion(Map.of("isLatestVersion", false))).isFalse();
    }
}
