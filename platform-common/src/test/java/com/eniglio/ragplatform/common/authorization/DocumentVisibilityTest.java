package com.eniglio.ragplatform.common.authorization;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentVisibilityTest {

    @Test
    void aDocumentWithNoVisibilityMetadataIsVisibleToEveryoneInTheTenant() {
        assertThat(DocumentVisibility.isVisibleTo(Map.of(), "some-user", List.of())).isTrue();
    }

    @Test
    void aTenantVisibleDocumentIsVisibleToEveryone() {
        Map<String, Object> metadata = Map.of("visibility", "TENANT");
        assertThat(DocumentVisibility.isVisibleTo(metadata, "some-user", List.of())).isTrue();
    }

    @Test
    void aRestrictedDocumentIsVisibleToItsOwner() {
        Map<String, Object> metadata = Map.of("visibility", "RESTRICTED", "userId", "owner-1");
        assertThat(DocumentVisibility.isVisibleTo(metadata, "owner-1", List.of())).isTrue();
    }

    @Test
    void aRestrictedDocumentIsVisibleToAUserExplicitlySharedWith() {
        Map<String, Object> metadata = Map.of(
                "visibility", "RESTRICTED", "userId", "owner-1", "sharedWith", List.of("shared-user"));
        assertThat(DocumentVisibility.isVisibleTo(metadata, "shared-user", List.of())).isTrue();
    }

    @Test
    void aRestrictedDocumentIsInvisibleToAnUnrelatedUserWithNoDepartment() {
        Map<String, Object> metadata = Map.of("visibility", "RESTRICTED", "userId", "owner-1");
        assertThat(DocumentVisibility.isVisibleTo(metadata, "other-user", List.of())).isFalse();
    }

    // --- docs/adr/0059-department-based-sharing.md ---

    @Test
    void aRestrictedDocumentSharedWithADepartmentIsVisibleToAUserInThatDepartment() {
        Map<String, Object> metadata = Map.of(
                "visibility", "RESTRICTED", "userId", "owner-1",
                "sharedWithDepartments", List.of("Financeiro"));
        assertThat(DocumentVisibility.isVisibleTo(metadata, "other-user", List.of("Financeiro"))).isTrue();
    }

    @Test
    void aRestrictedDocumentSharedWithADepartmentIsInvisibleToAUserInADifferentDepartment() {
        Map<String, Object> metadata = Map.of(
                "visibility", "RESTRICTED", "userId", "owner-1",
                "sharedWithDepartments", List.of("Financeiro"));
        assertThat(DocumentVisibility.isVisibleTo(metadata, "other-user", List.of("TI"))).isFalse();
    }

    @Test
    void aRestrictedDocumentSharedWithADepartmentIsInvisibleToAUserWithNoDepartment() {
        Map<String, Object> metadata = Map.of(
                "visibility", "RESTRICTED", "userId", "owner-1",
                "sharedWithDepartments", List.of("Financeiro"));
        assertThat(DocumentVisibility.isVisibleTo(metadata, "other-user", List.of())).isFalse();
    }

    @Test
    void departmentAndIndividualSharingBothGrantAccessIndependently() {
        Map<String, Object> metadata = Map.of(
                "visibility", "RESTRICTED", "userId", "owner-1",
                "sharedWith", List.of("shared-user"),
                "sharedWithDepartments", List.of("Financeiro"));
        assertThat(DocumentVisibility.isVisibleTo(metadata, "shared-user", List.of())).isTrue();
        assertThat(DocumentVisibility.isVisibleTo(metadata, "someone-else", List.of("Financeiro"))).isTrue();
        assertThat(DocumentVisibility.isVisibleTo(metadata, "nobody", List.of("TI"))).isFalse();
    }

    // --- docs/adr/0060-multi-department-membership-and-approval.md ---

    @Test
    void aUserBelongingToSeveralDepartmentsIsVisibleIfAnyOneOfThemMatches() {
        Map<String, Object> metadata = Map.of(
                "visibility", "RESTRICTED", "userId", "owner-1",
                "sharedWithDepartments", List.of("Financeiro"));
        assertThat(DocumentVisibility.isVisibleTo(metadata, "other-user", List.of("Financeiro", "TI"))).isTrue();
    }

    @Test
    void aUserWhoseDepartmentsNoneMatchIsStillInvisible() {
        Map<String, Object> metadata = Map.of(
                "visibility", "RESTRICTED", "userId", "owner-1",
                "sharedWithDepartments", List.of("Financeiro"));
        assertThat(DocumentVisibility.isVisibleTo(metadata, "other-user", List.of("TI", "RH"))).isFalse();
    }
}
