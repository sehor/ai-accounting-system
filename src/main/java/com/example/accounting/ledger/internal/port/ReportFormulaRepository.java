package com.example.accounting.ledger.internal.port;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for the report formula snapshot, its unique draft, its immutable
 * published history and the concrete account references of each revision.
 */
public interface ReportFormulaRepository {

    record Snapshot(
            UUID id, UUID ledgerId, String code, String name, String formulaJson,
            String formulaKind, int schemaVersion, int publishedVersion,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, UUID updatedBy) {
    }

    record Revision(
            UUID id, UUID formulaId, String state, String definitionJson,
            int basePublishedVersion, Long draftVersion, Integer publishedVersion,
            String source, Integer rollbackOfVersion, Long lastPreviewedDraftVersion,
            boolean previewHasWarnings, UUID createdBy, UUID updatedBy,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    Optional<Snapshot> findSnapshot(UUID ledgerId, String code);

    /** Row-level lock of the snapshot, for publish transactions. */
    Optional<Snapshot> lockSnapshot(UUID ledgerId, String code);

    /** Migration entry point: rewrites the snapshot to a canonical schema-1 definition. */
    void updateSnapshotDefinition(UUID snapshotId, String formulaKind, String canonicalJson, UUID actorId);

    /**
     * Publish entry point: rewrites the snapshot's current definition and bumps
     * its published version in one statement.
     */
    void publishSnapshot(UUID snapshotId, String formulaKind, String canonicalJson,
                         int publishedVersion, UUID actorId);

    /** Creates the snapshot and its published version 1 (new ledger initialization). */
    void createSnapshotWithPublishedVersion(
            UUID ledgerId, String code, String name, String formulaKind, String canonicalJson, UUID actorId);

    boolean publishedVersionExists(UUID ledgerId, String code, int version);

    Optional<Revision> findDraft(UUID ledgerId, String code);

    Optional<Revision> lockDraft(UUID ledgerId, String code);

    /** Creates the unique draft at draft version 1. */
    UUID createDraft(UUID snapshotId, String definitionJson, int basePublishedVersion, UUID actorId);

    /**
     * Optimistic draft update: succeeds only when {@code expectedDraftVersion}
     * still matches, then bumps the draft version and clears the last preview state.
     */
    boolean updateDraft(UUID draftId, String definitionJson, long expectedDraftVersion, UUID actorId);

    /** Marks the last previewed draft version and warning flag after a successful trial. */
    void updateDraftPreviewState(UUID draftId, long previewedDraftVersion, boolean hasWarnings);

    boolean deleteDraft(UUID draftId);

    /** Inserts an immutable published revision and returns its id; a no-op when the version exists. */
    UUID insertPublished(UUID formulaId, String definitionJson, int basePublishedVersion,
                         int publishedVersion, String source, Integer rollbackOfVersion, UUID actorId);

    /** Appends an audit revision for a REPORT_FORMULA aggregate. */
    void recordAudit(UUID ledgerId, UUID formulaId, String action, UUID actorId,
                     String beforeJson, String afterJson);

    List<Revision> listPublishedVersions(UUID ledgerId, String code, int page, int pageSize);

    long countPublishedVersions(UUID ledgerId, String code);

    Optional<Revision> findPublishedVersion(UUID ledgerId, String code, int version);

    /** Replaces the concrete account reference index of one revision. */
    void replaceAccountReferences(UUID revisionId, UUID ledgerId, Set<UUID> accountIds);

    /** Concrete account ids referenced by any published or draft revision of a formula. */
    Set<UUID> referencedAccountIds(UUID ledgerId, String code);

    /** True when any revision of the formula references the account. */
    boolean accountReferenced(UUID ledgerId, String code, UUID accountId);
}
