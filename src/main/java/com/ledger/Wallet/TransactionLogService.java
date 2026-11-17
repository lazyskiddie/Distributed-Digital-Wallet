package com.ledger.Wallet;

import com.ledger.Wallet.dto.TransferRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionLogService {

    private final TransactionLogRepository transactionLogRepository;

    // Own transaction so the audit row survives even if the transfer rolls back.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(TransferRequest request, String ref, String status) {
        transactionLogRepository.save(TransactionLog.builder()
                .transactionReference(ref)
                .sourceAccount(request.sourceAccount())
                .destinationAccount(request.destinationAccount())
                .amount(request.amount())
                .status(status)
                .build());
    }
}
