package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClassEndToEndTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void createsTheGeneratedSubclassWithConstructorArguments() {
        ClassOrderService service = Resilient.create(ClassOrderService.class, registry, "us", 0);

        assertThat(service).isInstanceOf(ClassOrderService.class);
        assertThat(service.getClass().getName()).isEqualTo(ClassOrderService.class.getName() + "$Resilient");
        assertThat(service.status("1")).isEqualTo("us:1");
        assertThat(Resilient.create(ClassOrderService.class, registry).status("2")).isEqualTo("eu:2");
        // the typed alternative: the generated constructor itself
        assertThat(new ClassOrderService$Resilient(registry, "ap", 0).status("3")).isEqualTo("ap:3");
    }

    @Test
    void classMethodAnnotationsWinOverTheInterfaceAttributeByAttribute() {
        ClassOrderService service = Resilient.create(ClassOrderService.class, registry, "eu", 10);

        // class method: maxAttempts = 4 (the interface method says 2), delay from the interface type
        assertThatIllegalStateException().isThrownBy(() -> service.status("1")).withMessage("status unavailable");
        assertThat(service.statusCalls).hasValue(4);
        // not annotated in the class: the interface method's three attempts
        assertThatIllegalStateException().isThrownBy(() -> service.quote("1")).withMessage("quote unavailable");
        assertThat(service.quoteCalls).hasValue(3);
    }

    @Test
    void retriesUntilTheCallSucceeds() {
        ClassOrderService service = Resilient.create(ClassOrderService.class, registry, "eu", 2);

        assertThat(service.status("1")).isEqualTo("eu:1");
        assertThat(service.statusCalls).hasValue(3);
    }

    @Test
    void selfInvocationGoesThroughThePolicies() {
        ClassOrderService service = Resilient.create(ClassOrderService.class, registry);

        // summary() is not protected; its own call to this.price(...) is retried and then falls back
        assertThat(service.summary("sku")).isEqualTo("summary of cached price of sku (pricing down)");
        assertThat(service.priceCalls).hasValue(3);

        ClassOrderService plain = new ClassOrderService();
        assertThatIllegalStateException().isThrownBy(() -> plain.summary("sku"));
        assertThat(plain.priceCalls).hasValue(1);
    }

    @Test
    void theCircuitBreakerOpensAndRejectsCalls() {
        ClassOrderService service = Resilient.create(ClassOrderService.class, registry);

        for (int i = 0; i < 2; i++) {
            assertThatIllegalStateException().isThrownBy(() -> service.inventory("1"));
        }
        assertThat(registry.circuitBreaker("dev.resilience4cata.examples.ClassOrderService#inventory(java.lang.String)")
                .state()).isEqualTo(CircuitBreakerState.OPEN);
        assertThatThrownBy(() -> service.inventory("1")).isInstanceOf(CallNotPermittedException.class);
        assertThat(service.inventoryCalls).hasValue(2);
    }

    @Test
    void aSlowCallTimesOut() {
        ClassOrderService service = Resilient.create(ClassOrderService.class, registry);

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.slow("1")).isInstanceOf(CallTimeoutException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void asyncMethodsRetryFallBackAndTimeOut() throws Exception {
        ClassOrderService service = Resilient.create(ClassOrderService.class, registry, "eu", 2);

        assertThat(service.track("p1").get(5, TimeUnit.SECONDS)).isEqualTo("parcel p1");
        assertThat(service.trackCalls).hasValue(3);
        assertThat(service.track("lost").get(5, TimeUnit.SECONDS)).isEqualTo("lost lost");
        assertThat(service.trackCalls).hasValue(6);
        assertThatThrownBy(() -> service.never().toCompletableFuture().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CallTimeoutException.class);
    }

    @Test
    void aClassWithoutAnnotationsOfItsOwnGetsNoSubclass() {
        assertThatIllegalStateException()
                .isThrownBy(() -> Resilient.create(ClassPlainOrderService.class, registry))
                .withMessageContaining("No generated class " + ClassPlainOrderService.class.getName() + "$Resilient")
                .withMessageContaining("annotationProcessor");

        // it still gets the interface's policies through the wrapper, but not for its calls to itself
        ClassPlainOrderService plain = new ClassPlainOrderService();
        ClassOrderApi wrapped = Resilient.of(ClassOrderApi.class, plain, registry);
        assertThat(wrapped.status("1")).isEqualTo("plain 1");
        assertThat(plain.flaky.calls()).isEqualTo(2);
    }
}
