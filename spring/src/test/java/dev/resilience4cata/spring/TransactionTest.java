package dev.resilience4cata.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.spring.tx.GuardedLedger;
import dev.resilience4cata.spring.tx.HedgedLedger;
import dev.resilience4cata.spring.tx.JakartaClassLedger;
import dev.resilience4cata.spring.tx.JakartaLedger;
import dev.resilience4cata.spring.tx.LedgerClient;
import dev.resilience4cata.spring.tx.OutsideLedger;
import dev.resilience4cata.spring.tx.ReactiveLedger;
import dev.resilience4cata.spring.tx.RetriedLedger;
import dev.resilience4cata.spring.tx.TransactionalLedgerClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** R4C-SPR-004: a retry or hedge of an annotated class must not run inside its own transaction proxy. */
public class TransactionTest {

    private final ApplicationContextRunner runner = Runners.runner().withUserConfiguration(Transactions.class);

    @Test
    void aClassLevelRetryOfATransactionalMethodFailsTheStartup() {
        runner.withBean(RetriedLedger.class).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageStartingWith("[R4C-SPR-004] Bean 'retriedLedger' (" + RetriedLedger.class.getName()
                        + ") has transactional methods that are retried or hedged: @Retry on post (REQUIRED)")
                .hasMessageContaining("UnexpectedRollbackException")
                .hasMessageContaining("resilience4cata.spring.allow-retry-inside-transaction=true"));
    }

    @Test
    void retriedAndHedgedMonoAndFluxMethodsOfATransactionalClassFailTheStartup() {
        runner.withBean(ReactiveLedger.class).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageStartingWith("[R4C-SPR-004] Bean 'reactiveLedger' (" + ReactiveLedger.class.getName()
                        + ") has transactional methods that are retried or hedged:")
                .hasMessageContaining("@Retry on post (REQUIRED)")
                .hasMessageContaining("@Hedge on read (REQUIRED)")
                .hasMessageContaining("@Retry on entries (REQUIRED)")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("guarded")));
    }

    @Test
    void aHedgedMethodOfATransactionalClassFailsTheStartup() {
        runner.withBean(HedgedLedger.class).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("@Hedge on read (REQUIRED)"));
    }

    /**
     * Spring reads Jakarta's annotation on the invoked method only (Spring's own {@code @Transactional} is
     * searched in overridden methods too); the generated override repeats the method's annotations, so a
     * method-level Jakarta {@code @Transactional} is seen and reported like Spring's.
     */
    @Test
    void methodLevelJakartaTransactionalIsSeenOnTheGeneratedOverride() {
        runner.withBean(JakartaLedger.class).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageStartingWith("[R4C-SPR-004] Bean 'jakartaLedger' (" + JakartaLedger.class.getName()
                        + ") has transactional methods that are retried or hedged: @Retry on post (REQUIRED)"));
    }

    @Test
    void methodLevelJakartaTransactionalStartsATransactionOnTheGeneratedOverride() {
        runner.withBean(JakartaLedger.class).withPropertyValues("resilience4cata.spring.allow-retry-inside-transaction=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JakartaLedger.class).post("a")).isEqualTo("posted a");
                    assertThat(context.getBean(Transactions.class).begun).hasValue(1);
                    assertThat(context.getBean(Transactions.class).committed).hasValue(1);
                });
    }

    @Test
    void jakartaTransactionalCountsToo() {
        runner.withBean(JakartaClassLedger.class).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("@Retry on post (REQUIRED)"));
    }

    @Test
    void notSupportedNeverAndPoliciesThatRunOnceAreAllowed() {
        runner.withBean(OutsideLedger.class).withBean(GuardedLedger.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(AopUtils.isAopProxy(context.getBean(OutsideLedger.class))).as("still transactional").isTrue();
            assertThat(context.getBean(GuardedLedger.class).post("a")).isEqualTo("a");
        });
    }

    @Test
    void theOptOutAllowsOneSharedTransaction() {
        runner.withBean(RetriedLedger.class).withPropertyValues("resilience4cata.spring.allow-retry-inside-transaction=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RetriedLedger.class).post("a")).isEqualTo("posted a");
                    assertThat(context.getBean(Transactions.class).begun).hasValue(1);
                });
    }

    @Test
    void excludedBeansAndContextsWithoutTransactionProxiesAreNotChecked() {
        Runners.runner().withBean(RetriedLedger.class).run(context -> assertThat(context).hasNotFailed());
        runner.withBean(RetriedLedger.class).withPropertyValues("resilience4cata.spring.exclude=retriedLedger")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void withoutSpringTxThereIsNoCheck() {
        Runners.runner().withBean(RetriedLedger.class)
                .withClassLoader(new FilteredClassLoader("org.springframework.transaction"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theInterfaceStyleRunsOneTransactionPerAttempt() {
        TransactionalLedgerClient impl = new TransactionalLedgerClient();
        runner.withBean("ledger", TransactionalLedgerClient.class, () -> impl).run(context -> {
            assertThat(context).hasNotFailed();
            impl.failures.set(2);

            assertThat(context.getBean(LedgerClient.class).post("a")).isEqualTo("posted a");
            Transactions transactions = context.getBean(Transactions.class);
            assertThat(transactions.begun).as("one transaction per attempt").hasValue(3);
            assertThat(transactions.rolledBack).hasValue(2);
            assertThat(transactions.committed).hasValue(1);
        });
    }

    /** {@code @EnableTransactionManagement} with a transaction manager that only counts. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    public static class Transactions {

        final AtomicInteger begun = new AtomicInteger();
        final AtomicInteger committed = new AtomicInteger();
        final AtomicInteger rolledBack = new AtomicInteger();

        @Bean
        PlatformTransactionManager transactionManager() {
            return new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    begun.incrementAndGet();
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                    committed.incrementAndGet();
                }

                @Override
                public void rollback(TransactionStatus status) {
                    rolledBack.incrementAndGet();
                }
            };
        }
    }
}
