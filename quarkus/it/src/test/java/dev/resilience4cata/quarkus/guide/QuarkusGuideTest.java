package dev.resilience4cata.quarkus.guide;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.testkit.ManualClock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

/** Runs the guide's application, with its application.properties and the test's ManualClock. */
@QuarkusTest
class QuarkusGuideTest {

    // tag::quarkus-test[]
    @Inject
    OrderService orders;

    @Inject
    ManualClock clock;

    @Inject
    OrderRepository repository; // fails the first loads it is told to

    @Test
    void retriesWithoutSleeping() {
        repository.failNext(2);
        long before = clock.elapsed().toNanos();

        assertThat(orders.find("42")).isEqualTo(new Order("42"));
        assertThat(clock.elapsed().toNanos()).isGreaterThan(before); // the two pauses, on the manual clock
    }
    // end::quarkus-test[]

    @Inject
    ResilienceRegistry registry;

    @Inject
    Checkout checkout;

    @Inject
    CardPayments card;

    @Inject
    PaymentsGateway payments;

    @Inject
    StockReport report;

    @Inject
    @RestClient
    StockClient rawStock;

    @Inject
    Alerts alerts;

    @Test
    void theAnnotatedClassIsTheGeneratedSubclass() {
        assertThat(orders.getClass().getName()).contains("OrderService$Resilient");
        repository.failNext(1);
        int loads = repository.loads();
        assertThat(orders.findAgain("7")).isEqualTo(new Order("7"));
        assertThat(repository.loads() - loads).as("a call on this is retried").isEqualTo(2);
    }

    @Test
    void theInterfaceIsInjectedThroughItsWrapper() {
        // the wrapper of an @ApplicationScoped bean is @ApplicationScoped too: behind its own client proxy
        assertThat(io.quarkus.arc.ClientProxy.unwrap(payments).getClass().getName()).endsWith("PaymentsGateway$Resilient");
        assertThat(checkout.pay("o-1")).isEqualTo("receipt-o-1");
        assertThat(registry.snapshot().retries()).extracting(r -> r.id())
                .contains("dev.resilience4cata.quarkus.guide.PaymentsGateway#charge(java.lang.String,long)");
    }

    @Test
    void theClassIsStillInjectableAsTheBeanItself() {
        assertThat(card.getClass().getName()).doesNotContain("$Resilient");
        assertThat(card.charge("o-2", 1)).isEqualTo("receipt-o-2");
    }

    @Test
    void theRestClientIsProtected() {
        StockResource.FAILURES.set(2);
        int requests = StockResource.REQUESTS.get();
        assertThat(report.line("abc")).isEqualTo("abc: 3");
        assertThat(StockResource.REQUESTS.get() - requests).isEqualTo(3);
        StockResource.FAILURES.set(1);
        org.assertj.core.api.Assertions.assertThatException().isThrownBy(() -> rawStock.available("abc"));
    }

    @Test
    void thePropertiesAndTheCustomizerConfigureTheRegistry() {
        assertThat(registry.retryConfig("").delay()).isEqualTo(Duration.ofMillis(50));
        assertThat(registry.circuitBreakerConfig("").minimumCalls()).isEqualTo(20);
        assertThat(registry.retryConfig("slow").maxAttempts()).as("the customizer").isEqualTo(5);
        assertThat(registry.retryConfig("slow").delay()).as("the properties win").isEqualTo(Duration.ofMillis(500));
        assertThat(registry.circuitBreaker("orders").config().failureRateThreshold()).isEqualTo(25f);
        assertThat(registry.retryConfig("").maxAttempts()).as("%dev is not active").isEqualTo(3);
        payments.charge("o-3", 1);
        assertThat(registry.snapshot().timeouts()).extracting(s -> s.config().timeout())
                .contains(Duration.ofSeconds(3)); // the instances key
    }

    @Test
    void theListenerGetsBreakerTransitions() {
        registry.circuitBreaker("orders").forceOpen();
        try {
            assertThat(alerts.sent).contains("orders is now FORCED_OPEN");
        } finally {
            registry.circuitBreaker("orders").reset();
        }
    }

    @Test
    void theHealthCheckIsUpWithAnOpenBreaker() {
        registry.circuitBreaker("orders").forceOpen();
        try {
            given().when().get("/q/health/ready").then().statusCode(200)
                    .body("checks.find { it.name == 'resilience4cata' }.status", equalTo("UP"))
                    .body("checks.find { it.name == 'resilience4cata' }.data.orders", equalTo("FORCED_OPEN"));
        } finally {
            registry.circuitBreaker("orders").reset();
        }
    }

    @Test
    void metricsAreExported() {
        orders.find("m");
        String metrics = given().when().get("/q/metrics").then().statusCode(200).extract().asString();
        assertThat(metrics).contains("r4c_circuitbreaker_state");
    }

    @Test
    void theShopEndpointsWork() {
        given().when().get("/shop/order/9").then().statusCode(200).body(equalTo("9"));
        given().when().get("/shop/stock/abcd").then().statusCode(200).body(equalTo("abcd: 4"));
    }
}
