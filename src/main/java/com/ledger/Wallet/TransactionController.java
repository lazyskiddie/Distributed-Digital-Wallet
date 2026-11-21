package com.ledger.Wallet;

import com.ledger.Wallet.dto.TransactionView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionLogRepository transactionLogRepository;

    /** The 20 most recent transfer attempts (successful and declined), newest first. */
    @GetMapping
    public List<TransactionView> recent() {
        return transactionLogRepository.findTop20ByOrderByIdDesc().stream()
                .map(t -> new TransactionView(t.getTransactionReference(), t.getSourceAccount(),
                        t.getDestinationAccount(), t.getAmount(), t.getStatus(), t.getTimestamp()))
                .toList();
    }
}

