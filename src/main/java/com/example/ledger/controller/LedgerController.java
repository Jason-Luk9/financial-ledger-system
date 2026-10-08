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

    @PostMapping("/transactions/{id}/reversal")
    ResponseEntity<TransactionView> reverse(@RequestHeader("Idempotency-Key") String key, @PathVariable Long id) {
        return response(ledger.reverse(key, id));
    }

    @GetMapping("/accounts/{id}")
    AccountView balance(@PathVariable Long id) { return ledger.balance(id); }

    @GetMapping("/accounts/{id}/transactions")
    HistoryPage history(@PathVariable Long id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ledger.history(id, page, size);
    }

    private ResponseEntity<TransactionView> response(PostingResult result) {
        return ResponseEntity.status(result.replay() ? 200 : 201)
                .header("Idempotency-Replayed", Boolean.toString(result.replay())).body(result.transaction());
    }
}
