package com.example.accounting.ledger;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.ledger.internal.port.LedgerBackupRepository;
import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LedgerBackupService {

    static final long MAX_ARCHIVE_BYTES = 100L * 1024 * 1024;
    static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;
    private static final String FORMAT = "AI-ACCOUNTING-LEDGER-BACKUP";
    private static final int FORMAT_VERSION = 1;
    private static final Set<LedgerRole> OWNER = Set.of(LedgerRole.OWNER);
    private static final List<TableDef> TABLES = List.of(
            new TableDef("cash_flow_item"),
            new TableDef("dimension_type"),
            new TableDef("dimension_value"),
            new TableDef("ledger_account"),
            new TableDef("ledger_account_dimension"),
            new TableDef("accounting_period"),
            new TableDef("opening_balance"),
            new TableDef("voucher"),
            new TableDef("voucher_line"),
            new TableDef("voucher_line_dimension"),
            new TableDef("voucher_approval"),
            new TableDef("period_action_audit"),
            new TableDef("report_formula_snapshot"),
            new TableDef("audit_revision"),
            new TableDef("document"),
            new TableDef("document_extraction"),
            new TableDef("agent_tool_audit"));

    private final LedgerAccessService access;
    private final IdentityService identities;
    private final LedgerBackupRepository repository;
    private final ObjectMapper objectMapper;
    private final Path storageRoot;

    public LedgerBackupService(
            LedgerAccessService access,
            IdentityService identities,
            LedgerBackupRepository repository,
            @Value("${storage.local.root:./data/files}") String storageRoot) {
        this.access = access;
        this.identities = identities;
        this.repository = repository;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.storageRoot = Path.of(storageRoot);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] backup(UUID actorId, UUID ledgerId) {
        if (!OWNER.contains(access.requireMembership(actorId, ledgerId))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "Only a ledger owner can create a backup");
        }
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.set("ledger", ledger(ledgerId));
            ObjectNode tables = data.putObject("tables");
            for (TableDef table : TABLES) {
                tables.set(table.name(), rows(table, ledgerId));
            }
            byte[] dataBytes = objectMapper.writeValueAsBytes(data);
            if (dataBytes.length > MAX_ATTACHMENT_BYTES) {
                throw tooLarge("The ledger data exceeds the 20 MiB metadata limit");
            }

            Map<String, byte[]> attachments = attachments(tables.withArray("document"));
            long totalBytes = dataBytes.length;
            for (byte[] content : attachments.values()) {
                totalBytes += content.length;
            }
            if (totalBytes > MAX_ARCHIVE_BYTES) {
                throw tooLarge("The uncompressed backup exceeds 100 MiB");
            }

            ObjectNode manifest = manifest(ledgerId, data.path("ledger").path("name").asText(),
                    dataBytes, tables.withArray("document"), attachments);
            return zip(manifest, dataBytes, attachments);
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (IOException exception) {
            throw problem(500, "LEDGER_BACKUP_FAILED", "Ledger backup failed",
                    "The ledger backup could not be created");
        }
    }

    private JsonNode ledger(UUID ledgerId) {
        String json = repository.ledgerJson(ledgerId);
        if (json == null) {
            throw problem(404, "LEDGER_NOT_FOUND", "Ledger not found", "The ledger is not available");
        }
        return readJson(json);
    }

    private ArrayNode rows(TableDef table, UUID ledgerId) {
        return (ArrayNode) readJson(repository.rowsJson(table.name(), ledgerId));
    }

    private Map<String, byte[]> attachments(ArrayNode documents) {
        Path root = storageRoot.toAbsolutePath().normalize();
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (JsonNode document : documents) {
            UUID documentId = uuid(document, "id");
            String objectKey = requiredText(document, "object_key");
            Path source = root.resolve(objectKey).normalize();
            if (!source.startsWith(root)) {
                throw invalid("A document object key escapes the storage root");
            }
            try {
                byte[] content = Files.readAllBytes(source);
                if (content.length > MAX_ATTACHMENT_BYTES
                        || content.length != document.path("size_bytes").asLong()
                        || !sha256(content).equals(document.path("sha256").asText())) {
                    throw problem(409, "LEDGER_BACKUP_CONTENT_MISSING", "Backup content is unavailable",
                            "A document is missing or does not match its metadata");
                }
                result.put("attachments/" + documentId, content);
            } catch (IOException exception) {
                throw problem(409, "LEDGER_BACKUP_CONTENT_MISSING", "Backup content is unavailable",
                        "A document file could not be read");
            }
        }
        return result;
    }

    private ObjectNode manifest(UUID ledgerId, String ledgerName, byte[] data,
                                ArrayNode documents, Map<String, byte[]> attachments) {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("format", FORMAT);
        manifest.put("version", FORMAT_VERSION);
        manifest.put("createdAt", OffsetDateTime.now().toString());
        manifest.put("sourceLedgerId", ledgerId.toString());
        manifest.put("ledgerName", ledgerName);
        manifest.put("dataSha256", sha256(data));
        ArrayNode files = manifest.putArray("attachments");
        for (JsonNode document : documents) {
            String entry = "attachments/" + requiredText(document, "id");
            byte[] content = attachments.get(entry);
            files.addObject()
                    .put("documentId", requiredText(document, "id"))
                    .put("entry", entry)
                    .put("size", content.length)
                    .put("sha256", sha256(content));
        }
        return manifest;
    }

    private byte[] zip(ObjectNode manifest, byte[] data, Map<String, byte[]> attachments) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            writeEntry(zip, "manifest.json", objectMapper.writeValueAsBytes(manifest));
            writeEntry(zip, "data.json", data);
            for (Map.Entry<String, byte[]> attachment : attachments.entrySet()) {
                writeEntry(zip, attachment.getKey(), attachment.getValue());
            }
            zip.finish();
            if (output.size() > MAX_ARCHIVE_BYTES) {
                throw tooLarge("The compressed backup exceeds 100 MiB");
            }
            return output.toByteArray();
        }
    }

    private void writeEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    @Transactional
    public LedgerResponses.Ledger restore(
            CurrentUserResolver.ResolvedUser actor, String requestedName,
            long declaredSize, InputStream input) {
        Archive archive = readArchive(declaredSize, input);
        ObjectNode source = (ObjectNode) archive.data().path("ledger");
        String name = restoreName(requestedName, requiredText(source, "name"));
        UUID ledgerId = UUID.randomUUID();
        try {
            identities.ensureUser(actor);
            createLedger(ledgerId, actor.id(), name, source);
            createOwner(ledgerId, actor.id());

            ObjectNode tables = (ObjectNode) archive.data().path("tables");
            Map<UUID, UUID> idMap = idMap(tables);
            idMap.put(uuid(source, "id"), ledgerId);
            restoreAttachments((ArrayNode) tables.path("document"), archive.entries());
            for (TableDef table : TABLES) {
                insertRows(table.name(), (ArrayNode) tables.path(table.name()),
                        ledgerId, actor.id(), idMap);
            }
            return new LedgerResponses.Ledger(
                    ledgerId, name, requiredText(source, "accounting_standard_code"),
                    requiredText(source, "accounting_standard_version"),
                    requiredText(source, "base_currency"),
                    LocalDate.parse(requiredText(source, "start_date")),
                    source.path("approval_enabled").asBoolean(), "ACTIVE");
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw problem(422, "LEDGER_RESTORE_FAILED", "Ledger restore failed",
                    "The backup data is incompatible with the current database schema");
        }
    }

    private String restoreName(String requestedName, String sourceName) {
        String suffix = "（恢复）";
        String name = requestedName == null || requestedName.isBlank()
                ? sourceName.substring(0, Math.min(sourceName.length(), 200 - suffix.length())) + suffix
                : requestedName.trim();
        if (name.isBlank() || name.length() > 200) {
            throw problem(400, "LEDGER_NAME_INVALID", "Invalid ledger name",
                    "The restored ledger name must contain 1 to 200 characters");
        }
        return name;
    }

    private void createLedger(UUID ledgerId, UUID actorId, String name, ObjectNode source) {
        repository.createLedger(ledgerId, actorId, name, requiredText(source, "accounting_standard_code"),
                requiredText(source, "accounting_standard_version"), requiredText(source, "base_currency"),
                LocalDate.parse(requiredText(source, "start_date")), source.path("approval_enabled").asBoolean(),
                "",
                source.path("account_level2_width").asInt(), source.path("account_level3_width").asInt(),
                source.path("account_level4_width").asInt());
    }

    private void createOwner(UUID ledgerId, UUID actorId) {
        repository.createOwner(ledgerId, actorId);
    }

    private Map<UUID, UUID> idMap(ObjectNode tables) {
        Map<UUID, UUID> result = new HashMap<>();
        for (TableDef table : TABLES) {
            for (JsonNode row : tables.path(table.name())) {
                if (row.hasNonNull("id")) {
                    UUID oldId = uuid(row, "id");
                    if (result.putIfAbsent(oldId, UUID.randomUUID()) != null) {
                        throw invalid("The backup reuses an identifier across business rows");
                    }
                }
            }
        }
        return result;
    }

    private void restoreAttachments(ArrayNode documents, Map<String, byte[]> entries) {
        Path root = storageRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            for (JsonNode value : documents) {
                ObjectNode document = (ObjectNode) value;
                UUID oldDocumentId = uuid(document, "id");
                byte[] content = entries.get("attachments/" + oldDocumentId);
                if (content == null) {
                    throw invalid("A document attachment is missing");
                }
                String objectKey = UUID.randomUUID().toString();
                Path target = root.resolve(objectKey).normalize();
                if (!target.startsWith(root)) {
                    throw invalid("A restored document path escapes the storage root");
                }
                Files.write(target, content, StandardOpenOption.CREATE_NEW);
                deleteFileIfTransactionRollsBack(target);
                document.put("object_key", objectKey);
            }
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (IOException exception) {
            throw problem(500, "LEDGER_RESTORE_FAILED", "Ledger restore failed",
                    "A restored attachment could not be stored");
        }
    }

    private void insertRows(String table, ArrayNode rows, UUID ledgerId, UUID actorId,
                            Map<UUID, UUID> idMap) {
        Map<String, ColumnDef> available = columns(table);
        for (JsonNode value : rows) {
            if (!value.isObject()) {
                throw invalid("A backup table contains a non-object row: " + table);
            }
            ObjectNode row = (ObjectNode) value;
            if ("ledger_account".equals(table) && row.hasNonNull("code")) {
                row.put("code", row.path("code").asText().replace(".", "").replace("-", ""));
            }
            Set<String> supplied = new HashSet<>();
            row.fieldNames().forEachRemaining(supplied::add);
            if (!available.keySet().containsAll(supplied)) {
                throw invalid("A backup row contains unsupported columns: " + table);
            }
            List<ColumnDef> selected = available.values().stream().filter(column -> row.has(column.name())).toList();
            LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
            Set<String> jsonColumns = new HashSet<>();
            for (ColumnDef column : selected) {
                converted.put(column.name(), convert(column, row.path(column.name()), ledgerId, actorId, idMap));
                if (column.json()) jsonColumns.add(column.name());
            }
            repository.insertRow(table, converted, jsonColumns);
        }
    }

    private Map<String, ColumnDef> columns(String table) {
        Map<String, ColumnDef> result = new LinkedHashMap<>();
        repository.columns(table).forEach((name, type) ->
                result.put(name, new ColumnDef(name, type, "jsonb".equals(type))));
        if (result.isEmpty()) {
            throw problem(500, "LEDGER_RESTORE_FAILED", "Ledger restore failed",
                    "A required backup table is unavailable");
        }
        return result;
    }

    private Object convert(ColumnDef column, JsonNode value, UUID ledgerId, UUID actorId,
                           Map<UUID, UUID> idMap) {
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        if ("uuid".equals(column.type())) {
            UUID original = UUID.fromString(value.asText());
            return switch (column.name()) {
                case "ledger_id" -> ledgerId;
                case "actor_id", "created_by", "updated_by", "posted_by" -> actorId;
                default -> idMap.getOrDefault(original, original);
            };
        }
        return switch (column.type()) {
            case "bool" -> value.asBoolean();
            case "int2" -> (short) value.asInt();
            case "int4" -> value.asInt();
            case "int8" -> value.asLong();
            case "numeric" -> value.decimalValue();
            case "date" -> LocalDate.parse(value.asText());
            case "timestamptz" -> OffsetDateTime.parse(value.asText());
            case "jsonb" -> value.toString();
            default -> value.asText();
        };
    }

    private void deleteFileIfTransactionRollsBack(Path target) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException ignored) {
                    }
                }
            }
        });
    }

    private Archive readArchive(long declaredSize, InputStream input) {
        if (declaredSize < 0 || declaredSize > MAX_ARCHIVE_BYTES) {
            throw tooLarge("The uploaded backup exceeds 100 MiB");
        }
        byte[] compressed = readBounded(input, MAX_ARCHIVE_BYTES);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        long[] total = {0};
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(compressed))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !validEntryName(entry.getName()) || !names.add(entry.getName())) {
                    throw invalid("The backup contains an invalid or duplicate ZIP entry");
                }
                if (entries.size() >= 1_000) {
                    throw tooLarge("The backup contains more than 1,000 entries");
                }
                entries.put(entry.getName(), readZipEntry(zip, MAX_ATTACHMENT_BYTES, total));
                zip.closeEntry();
            }
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid("The uploaded file is not a readable ZIP archive");
        }

        byte[] manifestBytes = entries.get("manifest.json");
        byte[] dataBytes = entries.get("data.json");
        if (manifestBytes == null || dataBytes == null) {
            throw invalid("manifest.json and data.json are required");
        }
        ObjectNode manifest = object(manifestBytes, "manifest.json");
        ObjectNode data = object(dataBytes, "data.json");
        validateArchive(manifest, data, dataBytes, entries);
        return new Archive(data, entries);
    }

    private byte[] readBounded(InputStream input, long maximum) {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maximum) {
                    throw tooLarge("The uploaded backup exceeds 100 MiB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid("The uploaded backup could not be read");
        }
    }

    private byte[] readZipEntry(ZipInputStream zip, long maximum, long[] archiveTotal) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long entryTotal = 0;
        int read;
        while ((read = zip.read(buffer)) >= 0) {
            entryTotal += read;
            archiveTotal[0] += read;
            if (entryTotal > maximum || archiveTotal[0] > MAX_ARCHIVE_BYTES) {
                throw tooLarge("The uncompressed backup exceeds its size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private boolean validEntryName(String name) {
        if ("manifest.json".equals(name) || "data.json".equals(name)) {
            return true;
        }
        if (!name.startsWith("attachments/") || name.length() != "attachments/".length() + 36) {
            return false;
        }
        try {
            UUID.fromString(name.substring("attachments/".length()));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private ObjectNode object(byte[] content, String entryName) {
        try {
            JsonNode node = objectMapper.readTree(content);
            if (!node.isObject()) {
                throw invalid(entryName + " must contain a JSON object");
            }
            return (ObjectNode) node;
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid(entryName + " contains invalid JSON");
        }
    }

    private void validateArchive(ObjectNode manifest, ObjectNode data, byte[] dataBytes,
                                 Map<String, byte[]> entries) {
        if (!FORMAT.equals(manifest.path("format").asText())
                || manifest.path("version").asInt(-1) != FORMAT_VERSION
                || !sha256(dataBytes).equals(requiredText(manifest, "dataSha256"))) {
            throw invalid("The backup format, version or data checksum is invalid");
        }
        if (!data.path("ledger").isObject() || !data.path("tables").isObject()) {
            throw invalid("The backup data structure is incomplete");
        }
        Set<String> allowedTables = TABLES.stream().map(TableDef::name).collect(java.util.stream.Collectors.toSet());
        Set<String> presentTables = new HashSet<>();
        data.path("tables").fieldNames().forEachRemaining(presentTables::add);
        if (!presentTables.equals(allowedTables)) {
            throw invalid("The backup table set is incomplete or unsupported");
        }
        for (String table : presentTables) {
            if (!data.path("tables").path(table).isArray()) {
                throw invalid("Backup table data must be an array: " + table);
            }
        }

        JsonNode files = manifest.path("attachments");
        if (!files.isArray()) {
            throw invalid("The attachment manifest is missing");
        }
        Set<String> declaredEntries = new HashSet<>();
        Set<String> declaredDocuments = new HashSet<>();
        for (JsonNode file : files) {
            String documentId = requiredText(file, "documentId");
            String entry = requiredText(file, "entry");
            try {
                UUID.fromString(documentId);
            } catch (IllegalArgumentException exception) {
                throw invalid("The attachment manifest contains an invalid document identifier");
            }
            if (!entry.equals("attachments/" + documentId)
                    || !declaredEntries.add(entry) || !declaredDocuments.add(documentId)) {
                throw invalid("The attachment manifest contains duplicate or invalid entries");
            }
            byte[] content = entries.get(entry);
            if (content == null || content.length != file.path("size").asLong(-1)
                    || !sha256(content).equals(requiredText(file, "sha256"))) {
                throw invalid("An attachment is missing or does not match its checksum");
            }
        }
        Set<String> archiveAttachments = new HashSet<>(entries.keySet());
        archiveAttachments.remove("manifest.json");
        archiveAttachments.remove("data.json");
        if (!archiveAttachments.equals(declaredEntries)) {
            throw invalid("The ZIP attachment set does not match the manifest");
        }
        Set<String> dataDocuments = new HashSet<>();
        for (JsonNode document : data.path("tables").path("document")) {
            dataDocuments.add(requiredText(document, "id"));
        }
        if (!dataDocuments.equals(declaredDocuments)) {
            throw invalid("The document data does not match the attachment manifest");
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException exception) {
            throw invalid("The backup contains invalid JSON");
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid("A required backup field is missing: " + field);
        }
        return value.asText();
    }

    private UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(requiredText(node, field));
        } catch (IllegalArgumentException exception) {
            throw invalid("A backup UUID is invalid: " + field);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw problem(500, "LEDGER_BACKUP_FAILED", "Ledger backup failed",
                    "SHA-256 is unavailable");
        }
    }

    private ApiProblemException invalid(String detail) {
        return problem(422, "LEDGER_BACKUP_INVALID", "Invalid ledger backup", detail);
    }

    private ApiProblemException tooLarge(String detail) {
        return problem(413, "LEDGER_BACKUP_TOO_LARGE", "Ledger backup is too large", detail);
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }

    private record TableDef(String name) {
    }

    private record Archive(ObjectNode data, Map<String, byte[]> entries) {
    }

    private record ColumnDef(String name, String type, boolean json) {
    }
}
