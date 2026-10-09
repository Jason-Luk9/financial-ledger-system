package com.example.ledger.service;

import static com.example.ledger.dto.ApiModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ledger.LedgerApplication;
import com.example.ledger.config.ReconciliationSchedulingConfig;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

class ReconciliationSchedulerTest {
    private final ReconciliationService reconciliation = mock(ReconciliationService.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final Clock clock = Clock.fixed(Instant.parse("2027-01-01T00:05:00Z"), ZoneId.of("Asia/Singapore"));
    private final Logger logger = (Logger) LoggerFactory.getLogger(ReconciliationScheduler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ReconciliationSchedulingConfig.class)
            .withBean(ReconciliationService.class, () -> reconciliation)
            .withBean(Clock.class, () -> clock)
            .withBean(TaskScheduler.class, () -> taskScheduler);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
        doReturn(mock(ScheduledFuture.class)).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void startupAndRegisteredMonthlyTaskReconcilePreviousUtcMonth() {
        when(reconciliation.monthly("2026-12")).thenReturn(report(true, "AVAILABLE"));
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ReconciliationScheduler.class);
            var runnable = ArgumentCaptor.forClass(Runnable.class);
            var trigger = ArgumentCaptor.forClass(Trigger.class);
            verify(taskScheduler).schedule(runnable.capture(), trigger.capture());
            assertThat(trigger.getValue()).isInstanceOf(CronTrigger.class);
            assertThat(((CronTrigger) trigger.getValue()).getExpression()).isEqualTo("0 5 0 1 * *");
            var triggerClock = Clock.fixed(Instant.parse("2026-12-31T12:00:00Z"), ZoneId.of("Asia/Singapore"));
            assertThat(trigger.getValue().nextExecution(new SimpleTriggerContext(triggerClock)))
                    .isEqualTo(Instant.parse("2027-01-01T00:05:00Z"));

            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(LedgerApplication.class),
                    new String[0], context.getSourceApplicationContext(), Duration.ZERO));
            runnable.getValue().run();
            verify(reconciliation, times(2)).monthly("2026-12");
            assertThat(logs.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).contains("month=2026-12", "healthy=true", "accountId=1", "discrepancy=0");
            });
            assertThat(logs.list.getFirst().getFormattedMessage()).contains("trigger=startup");
            assertThat(logs.list.getLast().getFormattedMessage()).contains("trigger=scheduled");
        });
    }

    @Test
    void configuredCronUsesUtc() {
        contextRunner.withPropertyValues("ledger.reconciliation.schedule.cron=0 10 1 1 * *").run(context -> {
            var trigger = ArgumentCaptor.forClass(Trigger.class);
            verify(taskScheduler).schedule(any(Runnable.class), trigger.capture());
            assertThat(trigger.getValue().nextExecution(new SimpleTriggerContext(clock)))
                    .isEqualTo(Instant.parse("2027-01-01T01:10:00Z"));
        });
    }

    @Test
    void disabledAutomationRegistersNeitherStartupListenerNorScheduledTask() {
        contextRunner.withPropertyValues("ledger.reconciliation.schedule.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ReconciliationScheduler.class);
            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(LedgerApplication.class),
                    new String[0], context.getSourceApplicationContext(), Duration.ZERO));
            verifyNoInteractions(reconciliation, taskScheduler);
        });
    }

    @Test
    void monthSelectionUsesUtcEvenWhenLocalCalendarHasRolledOver() {
        var singaporeNewYear = Clock.fixed(Instant.parse("2026-12-31T16:05:00Z"), ZoneId.of("Asia/Singapore"));
        when(reconciliation.monthly("2026-11"))
                .thenReturn(new MonthlyReport("2026-11", "UTC", true, List.of(account("AVAILABLE", BigDecimal.ZERO)), List.of()));
        var scheduler = new ReconciliationScheduler(reconciliation, singaporeNewYear);
        scheduler.onStartup();
        scheduler.onSchedule();
        verify(reconciliation, times(2)).monthly("2026-11");
    }

    @Test
    void discrepanciesAreLoggedWithAccountFiguresAndIssues() {
        when(reconciliation.monthly("2026-12")).thenReturn(report(false, "AVAILABLE"));
        new ReconciliationScheduler(reconciliation, clock).onSchedule();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("month=2026-12", "healthy=false", "discrepancy=5", "Closing balance mismatch");
        });
    }

    @Test
    void monthBeforeAccountCreationIsAnInformationalSkip() {
        when(reconciliation.monthly("2026-12")).thenReturn(report(false, "UNAVAILABLE"));
        new ReconciliationScheduler(reconciliation, clock).onStartup();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("skipped", "month=2026-12", "reason=accounts-not-yet-open");
        });
    }

    @Test
    void executionFailureDoesNotPreventStartupOrLaterChecks() {
        when(reconciliation.monthly("2026-12"))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(report(true, "AVAILABLE"));
        contextRunner.run(context -> {
            assertThatCode(() -> context.publishEvent(new ApplicationReadyEvent(new SpringApplication(LedgerApplication.class),
                    new String[0], context.getSourceApplicationContext(), Duration.ZERO))).doesNotThrowAnyException();
            context.getBean(ReconciliationScheduler.class).onSchedule();
            verify(reconciliation, times(2)).monthly("2026-12");
            assertThat(logs.list).hasSize(2);
            assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
            assertThat(logs.list.getFirst().getFormattedMessage()).contains("failed month=2026-12", "trigger=startup");
            assertThat(logs.list.getFirst().getThrowableProxy().getMessage()).isEqualTo("database unavailable");
            assertThat(logs.list.getLast().getLevel()).isEqualTo(Level.INFO);
            assertThat(logs.list.getLast().getFormattedMessage()).contains("healthy=true");
        });
    }

    private MonthlyReport report(boolean healthy, String status) {
        return new MonthlyReport("2026-12", "UTC", healthy,
                List.of(account(status, healthy ? BigDecimal.ZERO : new BigDecimal("5"))),
                healthy ? List.of() : List.of("Closing balance mismatch for account 1"));
    }

    private MonthlyAccount account(String status, BigDecimal discrepancy) {
        if (status.equals("UNAVAILABLE")) return new MonthlyAccount(1L, "USD", status, null, null, null, null, null, null);
        return new MonthlyAccount(1L, "USD", status, new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("140"),
                new BigDecimal("860"), new BigDecimal("860").add(discrepancy), discrepancy);
    }
}
