package com.example.accounting.exchange;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}/data-exchange")
public class DataExchangeController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CurrentUserResolver currentUserResolver;
    private final KingdeeExchange kingdee;

    public DataExchangeController(CurrentUserResolver currentUserResolver, KingdeeExchange kingdee) {
        this.currentUserResolver = currentUserResolver;
        this.kingdee = kingdee;
    }

    @PostMapping(value = "/kingdee:import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KingdeeExchange.ImportResult importKingdee(
            HttpServletRequest request, @PathVariable UUID ledgerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestPart("file") MultipartFile file) throws IOException {
        return kingdee.importKingdee(currentUserResolver.resolve(request), ledgerId, idempotencyKey,
                file.getSize(), file.getInputStream());
    }

    @GetMapping(value = "/kingdee:export", produces =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportKingdee(
            HttpServletRequest request, @PathVariable UUID ledgerId,
            @RequestParam(defaultValue = "false") boolean mergeEntries,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"kingdee-vouchers.xlsx\"")
                .body(kingdee.exportKingdee(currentUserResolver.resolve(request), ledgerId, mergeEntries,
                        startDate, endDate));
    }
}
