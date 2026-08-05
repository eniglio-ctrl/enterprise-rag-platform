package com.eniglio.ragplatform.common.authorization;

import java.util.Collection;
import java.util.Map;

/**
 * Resource-level authorization (docs/ROADMAP.md item #24) — a lightweight ABAC
 * model, not RBAC: every document chunk's {@code metadata} carries an owner
 * ({@code "userId"}, already stamped by {@code DocumentIngestionService} since
 * before this existed), a {@code "visibility"} ({@link #TENANT}/{@link #RESTRICTED}),
 * and an optional {@code "sharedWith"} list of specific user IDs. Shared between
 * {@code ingestion-service} (writes/updates it) and {@code rag-service} (reads it at
 * retrieval time) so both agree on the exact same string values and metadata key
 * names — a typo in one service silently failing to match the other would otherwise
 * be a real, hard-to-notice authorization bug.
 * <p>
 * {@link #TENANT} is the default (every document ingested before this existed has
 * no {@code "visibility"} key at all) and preserves this project's original,
 * honestly-scoped authorization model (ADR 0007): visible to every authenticated
 * user in the same tenant. {@link #RESTRICTED} narrows that to just the owner plus
 * whoever is explicitly named in {@code sharedWith} — a deliberate, separate action
 * taken after upload, not an upload-time choice, so the upload endpoint's own
 * contract never had to change.
 */
public final class DocumentVisibility {

    public static final String TENANT = "TENANT";
    public static final String RESTRICTED = "RESTRICTED";

    public static final String VISIBILITY_KEY = "visibility";
    public static final String SHARED_WITH_KEY = "sharedWith";
    public static final String OWNER_KEY = "userId";

    private DocumentVisibility() {
    }

    /**
     * {@code true} unless {@code metadata}'s own {@code "visibility"} is exactly
     * {@link #RESTRICTED} — any other value, including a missing key entirely
     * (every chunk ingested before this feature existed), means "visible tenant-wide",
     * matching the original model exactly. Tenant isolation itself (ADR 0007) is
     * enforced separately, upstream of this check, by the existing {@code tenant_id}
     * filter — this only ever narrows *within* a tenant a caller already belongs to.
     */
    public static boolean isVisibleTo(Map<String, Object> metadata, String userId) {
        if (!RESTRICTED.equals(metadata.get(VISIBILITY_KEY))) {
            return true;
        }
        if (userId != null && userId.equals(metadata.get(OWNER_KEY))) {
            return true;
        }
        Object sharedWith = metadata.get(SHARED_WITH_KEY);
        return sharedWith instanceof Collection<?> collection && collection.contains(userId);
    }
}
