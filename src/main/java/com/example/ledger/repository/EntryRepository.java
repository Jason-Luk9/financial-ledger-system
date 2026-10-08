package com.example.ledger.repository;

import com.example.ledger.model.LedgerEntry;
import java.util.List;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface EntryRepository extends JpaRepository<LedgerEntry, Long> {
    @Query("select e from LedgerEntry e join LedgerTransaction t on t.id = e.transactionId "
            + "where e.accountId = :accountId order by t.createdAt, e.id")
    Page<LedgerEntry> history(Long accountId, Pageable pageable);

    @Query("select e from LedgerEntry e join LedgerTransaction t on t.id = e.transactionId "
            + "order by t.createdAt, e.id")
    List<LedgerEntry> chronologicalEntries();
}
