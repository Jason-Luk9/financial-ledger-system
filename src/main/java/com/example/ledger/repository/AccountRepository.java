package com.example.ledger.repository;

import com.example.ledger.model.Account;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.*;

public interface AccountRepository extends JpaRepository<Account, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a order by a.id")
    List<Account> lockAccounts();
}
