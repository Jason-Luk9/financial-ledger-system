package com.example.ledger.config;

import com.example.ledger.service.ReconciliationScheduler;
import com.example.ledger.service.ReconciliationService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "ledger.reconciliation.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationSchedulingConfig {
    @Bean
    ReconciliationScheduler reconciliationScheduler(ReconciliationService reconciliation, Clock clock) {
        return new ReconciliationScheduler(reconciliation, clock);
    }
}
