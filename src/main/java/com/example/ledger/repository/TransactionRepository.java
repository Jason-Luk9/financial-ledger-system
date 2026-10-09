package com.example.ledger.repository;

import com.example.ledger.model.LedgerTransaction;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransactionRepository extends JpaRepository<LedgerTransaction, Long> {
    Optional<LedgerTransaction> findByIdempotencyKey(String key);
    boolean existsByReversalOf(Long originalId);

    @Query("select max(t.createdAt) from LedgerTransaction t")
    Optional<Instant> latestTimestamp();
}
