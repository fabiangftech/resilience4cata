package dev.resilience4cata.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.micronaut.fixtures.AuditInterceptor;
import dev.resilience4cata.micronaut.fixtures.AuditedLedger;
import dev.resilience4cata.micronaut.fixtures.Catalog;
import dev.resilience4cata.micronaut.fixtures.InventoryService;
import dev.resilience4cata.micronaut.fixtures.Ledger;
import dev.resilience4cata.micronaut.fixtures.Outage;
import dev.resilience4cata.micronaut.fixtures.PricingService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Annotated classes compiled with {@code -Ar4c.inject=micronaut}: Micronaut creates the generated subclass in place
 * of the class, with every injection feature of the class; and the manual way, a factory.
 */
class AnnotatedClassesTest {

    @Test
    void theGeneratedSubclassReplacesTheClassWithFieldInjectionPostConstructAndSelfInvocation() {
        try (ApplicationContext context = ApplicationContext.run()) {
            InventoryService inventory = context.getBean(InventoryService.class);
            Outage outage = context.getBean(Outage.class);
            assertThat(inventory.getClass().getName()).isEqualTo(InventoryService.class.getName() + "$Resilient");
            assertThat(context.getBeansOfType(InventoryService.class)).hasSize(1);
            assertThat(inventory.started()).as("@PostConstruct of the class").isTrue();

            outage.failNext(2);
            assertThat(inventory.stock("sku")).isEqualTo(42);
            assertThat(outage.calls()).isEqualTo(3);

            outage.failNext(1);
            assertThat(inventory.twice("sku")).as("this.stock() goes through the policies").isEqualTo(84);
        }
    }

    @Test
    void constructorInjectionKeepsQualifiersValuesAndTheChosenConstructor() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("pricing.margin", "7"))) {
            PricingService pricing = context.getBean(PricingService.class, Qualifiers.byName("pricing"));
            assertThat(pricing.getClass().getSimpleName()).isEqualTo("PricingService$Resilient");
            assertThat(pricing.currency()).isEqualTo("EUR");
            context.getBean(Outage.class).failNext(1);
            assertThat(pricing.price("sku")).isEqualTo(107);
        }
    }

    @Test
    void aFactoryCanReturnTheGeneratedSubclass() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Catalog catalog = context.getBean(Catalog.class);
            assertThat(catalog.getClass().getSimpleName()).isEqualTo("Catalog$Resilient");
            context.getBean(Outage.class).failNext(1);
            assertThat(catalog.describe("a")).isEqualTo("a");
        }
    }

    @Test
    void micronautAopGoesAroundTheSubclassPoliciesButInsideAnInterfaceWrapper() {
        try (ApplicationContext context = ApplicationContext.run()) {
            AuditInterceptor audit = context.getBean(AuditInterceptor.class);
            Outage outage = context.getBean(Outage.class);

            AuditedLedger ledger = context.getBean(AuditedLedger.class);
            assertThat(ledger).as("Micronaut's AOP proxy").isInstanceOf(io.micronaut.aop.Intercepted.class);
            assertThat(ledger.getClass().getSuperclass().getSimpleName()).isEqualTo("AuditedLedger$Resilient");
            outage.failNext(2);
            assertThat(ledger.post(5)).isEqualTo(5);
            assertThat(audit.calls("AuditedLedger$Resilient.post")).as("one advice call for three attempts").isEqualTo(1);

            Ledger remote = context.getBean(Ledger.class);
            outage.failNext(2);
            assertThat(remote.post(5)).isEqualTo(5);
            assertThat(audit.calls("RemoteLedger.post")).as("one advice call per attempt").isEqualTo(3);
        }
    }

    @Test
    void retriesStillStopAtTheirLimit() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Outage.class).failNext(10);
            assertThatThrownBy(() -> context.getBean(InventoryService.class).stock("sku"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(context.getBean(Outage.class).calls()).isEqualTo(3);
        }
    }
}
