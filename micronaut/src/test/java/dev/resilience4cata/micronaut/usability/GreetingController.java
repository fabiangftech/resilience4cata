package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;

/**
 * A controller: {@code /greeting} fails as told by {@link Failures}; {@code /stock/{sku}} and {@code /price/{sku}}
 * are annotated methods of the controller, so the generated subclass serves them; {@code /stock} binds its path
 * variable by the parameter's name, {@code /price} names its parameters.
 */
@Controller("/usability")
@Requires(property = "test.usability", value = "http")
public class GreetingController {

    private final Failures failures;

    public GreetingController(Failures failures) {
        this.failures = failures;
    }

    @Get("/greeting")
    public String greeting() {
        failures.call();
        return "hello";
    }

    @Get("/stock/{sku}")
    @Retry(maxAttempts = 3)
    public String stock(@PathVariable String sku) {
        failures.call();
        return sku + "=42";
    }

    @Get("/price/{sku}")
    @Retry(maxAttempts = 3)
    public String price(@PathVariable("sku") String sku, @QueryValue("currency") String currency) {
        failures.call();
        return sku + "=7 " + currency;
    }
}
