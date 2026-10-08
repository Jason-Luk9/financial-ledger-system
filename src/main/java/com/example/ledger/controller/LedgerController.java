package com.example.ledger.controller;

import com.example.ledger.service.LedgerService;
import static com.example.ledger.dto.ApiModels.*;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class LedgerController {
    private final LedgerService ledger;

    LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/transactions")
    ResponseEntity<TransactionView> transfer(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TransferRequest request) {
        return response(ledger.transfer(key, request));
    }

    private ResponseEntity<TransactionView> response(PostingResult result) {
        return ResponseEntity.status(result.replay() ? 200 : 201)
                .header("Idempotency-Replayed", Boolean.toString(result.replay())).body(result.transaction());
    }
}
