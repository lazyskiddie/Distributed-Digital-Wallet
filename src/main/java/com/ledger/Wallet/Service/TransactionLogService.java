package com.ledger.Wallet.Service;

import com.ledger.Wallet.Repository.TransactionLogRepository;
import com.ledger.Wallet.Model.TransactionLog;
import com.ledger.Wallet.dto.TransferRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionLogService {

    private final TransactionLogRepository transactionLogRepository;

    // Joins the transfer transaction for SUCCESS; for FAILED it is called after rollback and gets its own.
    @Transactional
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
