package com.example.accounting.reporting.internal.application;

import com.example.accounting.shared.balance.BalanceProjectionService;
import com.example.accounting.reporting.internal.persistence.BalanceProjectionException;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultBalanceProjectionService implements BalanceProjectionService {

    private final BalanceProjectionRepository repository;

    public DefaultBalanceProjectionService(BalanceProjectionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void publishVoucher(VoucherEvent event) {
        call(() -> repository.appendVoucherEvent(event));
    }

    @Override
    @Transactional
    public void publishOpeningBalances(OpeningBalanceEvent event) {
        call(() -> repository.appendOpeningBalanceEvent(event));
    }

    @Override
    @Transactional
    public void requireOpenPeriod(UUID ledgerId, UUID periodId) {
        call(() -> repository.requireOpenPeriod(ledgerId, periodId));
    }

    @Override
    @Transactional
    public void requireReadyForClose(UUID ledgerId, UUID periodId) {
        call(() -> repository.requireReadyForClose(ledgerId, periodId));
    }

    @Override
    @Transactional
    public void markReopened(UUID ledgerId, UUID periodId) {
        call(() -> repository.markReopened(ledgerId, periodId));
    }

    @Override
    public ProjectionStatus status(UUID ledgerId, String periodCode) {
        return repository.status(ledgerId, periodCode);
    }

    private void call(Runnable operation) {
        try {
            operation.run();
        } catch (BalanceProjectionException exception) {
            throw new ApiProblemException(409, exception.code(), "Balance projection rejected", exception.getMessage(), false);
        }
    }
}
