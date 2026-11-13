package pilot.micronaut;

import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.runtime.Micronaut;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.io.IOException;
import pilot.common.Probe;

@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class App {

    private final Inventory inventory;
    private final ResilienceRegistry registry;

    App(Inventory inventory, ResilienceRegistry registry) {
        this.inventory = inventory;
        this.registry = registry;
    }

    public static void main(String[] args) {
        Micronaut.run(App.class, args);
    }

    @Get(value = "/orders/{id}", produces = MediaType.TEXT_PLAIN)
    String order(@PathVariable String id) throws IOException, InterruptedException {
        return inventory.stock(id);
    }

    @Get(value = "/quotes/{id}", produces = MediaType.TEXT_PLAIN)
    String quote(@PathVariable String id) throws IOException, InterruptedException {
        return inventory.quote(id);
    }

    @Get(value = "/pilot/stats", produces = MediaType.APPLICATION_JSON)
    String stats() {
        return Probe.stats(registry.snapshot().toMap());
    }

    @Error(exception = RateLimitExceededException.class)
    HttpResponse<String> limited() {
        return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS).body("limited");
    }

    @Error(exception = IOException.class)
    HttpResponse<String> failed() {
        return HttpResponse.status(HttpStatus.BAD_GATEWAY).body("failed");
    }
}
