package com.example.ledger.repository;

import com.example.ledger.model.LedgerTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<LedgerTransaction, Long> {
    Optional<LedgerTransaction> findByIdempotencyKey(String key);
    boolean existsByReversalOf(Long originalId);
}
