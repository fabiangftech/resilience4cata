package dev.resilience4cata.quarkus.usability;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.OrdersResource;
import dev.resilience4cata.quarkus.usability.fixtures.OrdersResource$Resilient;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A Quarkus REST resource without a scope annotation, the form of most Quarkus guides: Quarkus makes it a
 * {@code @Singleton} bean by itself. Its endpoints should go through the policies.
 */
@Disabled("BUG-QK-4: Quarkus REST serves annotated resource classes with an instance of the vetoed original class:"
        + " the endpoints' policies silently do nothing")
class UnscopedRestResourceTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, OrdersResource.class, OrdersResource$Resilient.class));

    @Test
    void anEndpointIsRetried() {
        int before = given().get("/orders/fail/2").then().statusCode(200).extract().asString().transform(Integer::parseInt);
        given().get("/orders/42").then().statusCode(200).body(equalTo("order 42"));
        given().get("/orders/fail/0").then().statusCode(200).body(equalTo(String.valueOf(before + 3)));
    }
}
