package dev.resilience4cata.examples.docs.annotatedclasses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The claims of docs/{en,es}/guides/annotated-classes.md. */
class AnnotatedClassesTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();

    @Test
    void selfInvocationIsIntercepted() {
        AtomicInteger counts = new AtomicInteger();
        Warehouse warehouse = sku -> {
            if (counts.incrementAndGet() % 2 == 1) {
                throw new IllegalStateException("warehouse busy");   // every first try fails
            }
            return 5;
        };
        // tag::annotated-classes-create[]
        StockService stock = Resilient.create(StockService.class, registry, warehouse);
        int total = stock.total(List.of("a", "b"));   // each stock(sku) call is retried
        // end::annotated-classes-create[]

        assertThat(total).isEqualTo(10);
        assertThat(counts).hasValue(4);
        assertThat(stock.getClass().getName()).isEqualTo(StockService.class.getName() + "$Resilient");

        AtomicInteger plainCounts = new AtomicInteger();
        StockService plain = new StockService(sku -> {
            plainCounts.incrementAndGet();
            throw new IllegalStateException("warehouse busy");
        });
        assertThatIllegalStateException().isThrownBy(() -> plain.total(List.of("a")));
        assertThat(plainCounts).as("the plain class is not protected").hasValue(1);
    }

    @Test
    void theGeneratedConstructorIsTheTypedAlternative() {
        Warehouse warehouse = sku -> 5;
        // tag::annotated-classes-constructor[]
        StockService stock = new StockService$Resilient(registry, warehouse);   // checked by the compiler
        // end::annotated-classes-constructor[]
        assertThat(stock.total(List.of("a", "b", "c"))).isEqualTo(15);
    }

    @Test
    void createRejectsArgumentsThatFitNoConstructor() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Resilient.create(StockService.class, registry, "not a warehouse"))
                .withMessageContaining("[R4C-RT-009]");
    }

    @Test
    void classAnnotationsWinOverTheInterfaceAttributeByAttribute() {
        OrderService orders = Resilient.create(OrderService.class, registry);

        assertThatIllegalStateException().isThrownBy(() -> orders.status("1"));
        assertThat(orders.calls).hasValue(4);

        orders.calls.set(0);
        assertThatIllegalStateException().isThrownBy(() -> orders.quote("1"));
        assertThat(orders.calls).hasValue(3);

        assertThat(orders.price("1")).isEqualTo("cached price of 1");
    }

    @Test
    void instanceIdsUseTheAnnotatedClass() {
        OrderService orders = Resilient.create(OrderService.class, registry);
        orders.price("1");
        assertThat(registry.snapshot().retries()).extracting(r -> r.id()).contains(
                "dev.resilience4cata.examples.docs.annotatedclasses.OrderService#status(java.lang.String)",
                "dev.resilience4cata.examples.docs.annotatedclasses.OrderService#quote(java.lang.String)");
    }

    @Test
    void theInterfaceStyleDoesNotSeeTheClassAnnotations() {
        OrderService impl = new OrderService();
        OrderApi wrapped = Resilient.of(OrderApi.class, impl, registry);

        assertThatIllegalStateException().isThrownBy(() -> wrapped.status("1"));
        assertThat(impl.calls).as("only the interface's maxAttempts = 2").hasValue(2);
    }
}
