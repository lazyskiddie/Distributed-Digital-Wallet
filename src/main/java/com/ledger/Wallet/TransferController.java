package com.ledger.Wallet;

import com.ledger.Wallet.dto.TransferRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity> initiateTransfer(@Valid @RequestBody TransferRequest request) {
        // The service handles the transaction, locking, and throws exceptions if funds are insufficient
        String transactionRef = transferService.transferFunds(request);

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "transactionReference", transactionRef,
                "message", "Transfer completed successfully."
        ));
    }
}