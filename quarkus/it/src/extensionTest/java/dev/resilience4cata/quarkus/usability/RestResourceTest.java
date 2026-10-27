package dev.resilience4cata.quarkus.usability;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.ScopedOrdersResource;
import dev.resilience4cata.quarkus.usability.fixtures.ScopedOrdersResource$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A Quarkus REST resource that is an annotated class with a scope. Quarkus REST finds resources by {@code @Path} in
 * the index, which only the original class carries, and that class is vetoed: so the endpoints should still be served
 * by the generated subclass, through the policies.
 */
@Disabled("BUG-QK-4: Quarkus REST serves annotated resource classes with an instance of the vetoed original class:"
        + " the endpoints' policies silently do nothing")
class RestResourceTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, ScopedOrdersResource.class,
                    ScopedOrdersResource$Resilient.class));

    @Test
    void theResourceIsTheGeneratedSubclass() {
        given().get("/scoped-orders/class").then().statusCode(200)
                .body(equalTo(ScopedOrdersResource$Resilient.class.getName()));
    }

    @Test
    void anEndpointIsRetried() {
        int before = given().get("/scoped-orders/fail/2").then().statusCode(200).extract().asString()
                .transform(Integer::parseInt);
        given().get("/scoped-orders/42").then().statusCode(200).body(equalTo("order 42"));
        given().get("/scoped-orders/fail/0").then().statusCode(200).body(equalTo(String.valueOf(before + 3)));
    }
}
