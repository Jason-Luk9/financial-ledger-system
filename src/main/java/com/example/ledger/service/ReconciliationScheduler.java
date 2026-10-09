package com.example.ledger.service;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

public class ReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);
    private final ReconciliationService reconciliation;
    private final Clock clock;

    public ReconciliationScheduler(ReconciliationService reconciliation, Clock clock) {
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reconcilePreviousMonth("startup");
    }

    @Scheduled(cron = "${ledger.reconciliation.schedule.cron:0 5 0 1 * *}", zone = "UTC")
    public void onSchedule() {
        reconcilePreviousMonth("scheduled");
    }

    private void reconcilePreviousMonth(String trigger) {
        String month = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1).toString();
        try {
            // Call the separate Spring service so its read-only, repeatable-read transaction applies.
            var report = reconciliation.monthly(month);
            if (!report.accounts().isEmpty()
                    && report.accounts().stream().allMatch(a -> "UNAVAILABLE".equals(a.historyStatus()))) {
                log.info("Monthly reconciliation skipped month={} trigger={} reason=accounts-not-yet-open accounts={}",
                        month, trigger, report.accounts());
            } else if (report.healthy()) {
                log.info("Monthly reconciliation month={} trigger={} healthy=true accounts={} issues={}",
                        month, trigger, report.accounts(), report.issues());
            } else {
                log.warn("Monthly reconciliation month={} trigger={} healthy=false accounts={} issues={}",
                        month, trigger, report.accounts(), report.issues());
            }
        } catch (RuntimeException ex) {
            log.error("Monthly reconciliation failed month={} trigger={}; retry via the manual endpoint, restart, or next scheduled run",
                    month, trigger, ex);
        }
    }
}
