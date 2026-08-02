package com.example.accounting.ledger;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}")
public class AccountExchangeController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CurrentUserResolver users;
    private final AccountExchangeService exchange;

    public AccountExchangeController(CurrentUserResolver users, AccountExchangeService exchange) {
        this.users = users;
        this.exchange = exchange;
    }

    @GetMapping("/account-import-template")
    public ResponseEntity<byte[]> template(
            HttpServletRequest request, @PathVariable UUID ledgerId,
            @RequestParam AccountExchangeService.Format format) {
        return download(exchange.template(user(request), ledgerId, format),
                "account-import-" + format.name().toLowerCase() + ".xlsx");
    }

    @GetMapping("/account-export")
    public ResponseEntity<byte[]> export(
            HttpServletRequest request, @PathVariable UUID ledgerId,
            @RequestParam AccountExchangeService.Format format) {
        return download(exchange.export(user(request), ledgerId, format),
                "accounts-" + format.name().toLowerCase() + ".xlsx");
    }

    @PostMapping(value = "/account-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AccountExchangeService.Preview preview(
            HttpServletRequest request, @PathVariable UUID ledgerId,
            @RequestParam AccountExchangeService.Format format,
            @RequestPart("file") MultipartFile file) throws IOException {
        return exchange.preview(user(request), ledgerId, format, file.getOriginalFilename(),
                file.getSize(), file.getInputStream());
    }

    @GetMapping("/account-imports/{importId}")
    public AccountExchangeService.Preview get(
            HttpServletRequest request, @PathVariable UUID ledgerId, @PathVariable UUID importId) {
        return exchange.get(user(request), ledgerId, importId);
    }

    @PutMapping("/account-imports/{importId}/rows/{rowNo}")
    public AccountExchangeService.Preview decide(
            HttpServletRequest request, @PathVariable UUID ledgerId, @PathVariable UUID importId,
            @PathVariable int rowNo,
            @Valid @RequestBody AccountExchangeService.Decision decision) {
        return exchange.decide(user(request), ledgerId, importId, rowNo, decision);
    }

    @PostMapping("/account-imports/{importId}:commit")
    public AccountExchangeService.Preview commit(
            HttpServletRequest request, @PathVariable UUID ledgerId, @PathVariable UUID importId) {
        return exchange.commit(user(request), ledgerId, importId);
    }

    private ResponseEntity<byte[]> download(byte[] body, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    private UUID user(HttpServletRequest request) {
        return users.resolve(request);
    }
}
