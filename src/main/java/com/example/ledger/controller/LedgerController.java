package com.example.ledger.controller;

import com.example.ledger.service.LedgerService;
import com.example.ledger.service.ReconciliationService;
import static com.example.ledger.dto.ApiModels.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid body, header, account selection, or query parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorView.class))),
        @ApiResponse(responseCode = "503", description = "Database operation failed; retry POST requests with the same key",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorView.class)))
})
class LedgerController {
    private final LedgerService ledger;
    private final ReconciliationService reconciliation;

    LedgerController(LedgerService ledger, ReconciliationService reconciliation) {
        this.ledger = ledger;
        this.reconciliation = reconciliation;
    }

    @PostMapping("/transactions")
    @Operation(summary = "Transfer money", tags = "Transactions",
            description = "Atomically debit the source and credit the destination using the configured rate. "
                    + "Retry the same key and payload to retrieve the original result; a new key makes a new transfer.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transfer posted",
                    headers = @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean", example = "false")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = TransactionView.class))),
            @ApiResponse(responseCode = "200", description = "Original transfer replayed; no additional money moved",
                    headers = @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean", example = "true")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = TransactionView.class))),
            @ApiResponse(responseCode = "404", description = "Account does not exist",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorView.class))),
            @ApiResponse(responseCode = "409", description = "Idempotency key was used for a different request",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorView.class))),
            @ApiResponse(responseCode = "422", description = "Insufficient funds, unsupported conversion, zero rounded output, or balance limit",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorView.class)))
    })
    ResponseEntity<TransactionView> transfer(
            @Parameter(description = "1–128 printable non-space ASCII characters. Reuse only for retries of this operation.", example = "swagger-transfer-1")
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TransferRequest request) {
        return response(ledger.transfer(key, request));
    }

    @PostMapping("/transactions/{id}/reversal")
    @Operation(summary = "Reverse a transfer", tags = "Transactions",
            description = "Post one full reversal, returning the original rounded amounts without fetching a new rate. "
                    + "The original recipient must still have enough funds. Partial reversals and reversals of reversals are unsupported.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reversal posted",
                    headers = @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean", example = "false")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = TransactionView.class))),
            @ApiResponse(responseCode = "200", description = "Original reversal replayed; no additional money moved",
                    headers = @Header(name = "Idempotency-Replayed", schema = @Schema(type = "boolean", example = "true")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = TransactionView.class))),
            @ApiResponse(responseCode = "404", description = "Original transaction does not exist",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorView.class))),
            @ApiResponse(responseCode = "409", description = "Conflicting key, already reversed, or attempted reversal of a reversal",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorView.class))),
            @ApiResponse(responseCode = "422", description = "Insufficient funds to return the original amounts, or balance limit",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorView.class)))
    })
    ResponseEntity<TransactionView> reverse(
            @Parameter(description = "Use a separate key from the transfer. Reuse this key when retrying this reversal.", example = "swagger-reversal-1")
            @RequestHeader("Idempotency-Key") String key,
            @Parameter(description = "ID returned by the original transfer", example = "1") @PathVariable Long id) {
        return response(ledger.reverse(key, id));
    }

    @GetMapping("/accounts/{id}")
    @Operation(summary = "Get account balance", tags = "Accounts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current balance in the account's currency", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "Account does not exist",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorView.class)))
    })
    AccountView balance(@Parameter(description = "1 for USD or 2 for SGD", example = "1") @PathVariable Long id) {
        return ledger.balance(id);
    }

    @GetMapping("/accounts/{id}/transactions")
    @Operation(summary = "Get transaction history", tags = "Accounts",
            description = "Oldest first, ordered by timestamp then entry ID. Includes signed changes and historical balance checkpoints.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated account history", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "Account does not exist",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorView.class)))
    })
    HistoryPage history(
            @Parameter(description = "1 for USD or 2 for SGD", example = "1") @PathVariable Long id,
            @Parameter(description = "Zero-based page number", schema = @Schema(minimum = "0")) @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Entries per page", schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size) {
        return ledger.history(id, page, size);
    }

    @GetMapping("/ledger/integrity")
    @Operation(summary = "Check ledger integrity", tags = "Reconciliation",
            description = "Compare current balances with opening balances plus recorded movements, separately by currency. "
                    + "Check entries, conversions, and reversals. Inspect healthy and issues; discrepancies still return HTTP 200.")
    @ApiResponse(responseCode = "200", description = "Integrity report; discrepancy is actual minus expected", useReturnTypeSchema = true)
    IntegrityReport integrity() { return reconciliation.integrity(); }

    @GetMapping("/reconciliation")
    @Operation(summary = "Reconcile a completed month", tags = "Reconciliation",
            description = "Reconstruct balances for a completed UTC month and compare historical checkpoints. "
                    + "Current/future months return 400. Months before account creation report UNAVAILABLE; reports never repair data.")
    @ApiResponse(responseCode = "200", description = "Monthly report; inspect healthy, issues, and each account's historyStatus", useReturnTypeSchema = true)
    MonthlyReport monthly(
            @Parameter(description = "Completed UTC calendar month (YYYY-MM)", example = "2026-09", schema = @Schema(pattern = "^\\d{4}-\\d{2}$"))
            @RequestParam String month) { return reconciliation.monthly(month); }

    private ResponseEntity<TransactionView> response(PostingResult result) {
        return ResponseEntity.status(result.replay() ? 200 : 201)
                .header("Idempotency-Replayed", Boolean.toString(result.replay())).body(result.transaction());
    }
}
