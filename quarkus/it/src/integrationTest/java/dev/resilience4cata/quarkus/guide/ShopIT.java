package dev.resilience4cata.quarkus.guide;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * The guide's application over HTTP, packaged: the jar with {@code quarkusIntTest}, the native image with
 * {@code testNative}. The annotated class, the interface wrapper, the REST client wrapper, the readiness check and
 * the metrics.
 */
@QuarkusIntegrationTest
class ShopIT {

    @Test
    void anAnnotatedClassRetriesItsFirstFailure() {
        given().get("/shop/order/9").then().statusCode(200).body(equalTo("9"));
    }

    @Test
    void aWrappedInterfaceAnswers() {
        given().get("/shop/pay/o1").then().statusCode(200);
    }

    @Test
    void theRestClientWrapperRetriesA503() {
        given().get("/shop/stock/abcd").then().statusCode(200).body(equalTo("abcd: 4"));
    }

    @Test
    void theRegistryHasThePolicies() {
        given().get("/shop/order/1").then().statusCode(200);
        assertThat(Integer.parseInt(given().get("/shop/instances").then().statusCode(200).extract().asString()))
                .isPositive();
    }

    @Test
    void theReadinessCheckIsUp() {
        given().get("/q/health/ready").then().statusCode(200)
                .body("checks.find { it.name == 'resilience4cata' }.status", equalTo("UP"));
    }

    @Test
    void metricsAreExported() {
        given().get("/shop/order/2").then().statusCode(200);
        assertThat(given().get("/q/metrics").then().statusCode(200).extract().asString())
                .contains("r4c_circuitbreaker_state");
    }
}
