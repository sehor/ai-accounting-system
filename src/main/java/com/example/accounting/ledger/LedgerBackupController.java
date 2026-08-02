package com.example.accounting.ledger;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1")
public class LedgerBackupController {

    public static final MediaType BACKUP_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.ai-accounting.ledger-backup+zip");

    private final CurrentUserResolver users;
    private final LedgerBackupService backups;

    public LedgerBackupController(CurrentUserResolver users, LedgerBackupService backups) {
        this.users = users;
        this.backups = backups;
    }

    @GetMapping("/ledgers/{ledgerId}/backup")
    public ResponseEntity<byte[]> backup(HttpServletRequest request, @PathVariable UUID ledgerId) {
        return ResponseEntity.ok()
                .contentType(BACKUP_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ledger-" + ledgerId + ".aibackup\"")
                .body(backups.backup(users.resolve(request), ledgerId));
    }

    @PostMapping(value = "/ledger-restores", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerResponses.Ledger restore(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String name) throws IOException {
        return backups.restore(users.resolveUser(request), name, file.getSize(), file.getInputStream());
    }
}
