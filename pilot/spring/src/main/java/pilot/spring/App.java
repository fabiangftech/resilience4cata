package pilot.spring;

import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import java.io.IOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pilot.common.Probe;

@SpringBootApplication
@RestController
public class App {

    private final Inventory inventory;
    private final ResilienceRegistry registry;

    public App(Inventory inventory, ResilienceRegistry registry) {
        this.inventory = inventory;
        this.registry = registry;
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @GetMapping(value = "/orders/{id}", produces = MediaType.TEXT_PLAIN_VALUE)
    String order(@PathVariable String id) throws IOException, InterruptedException {
        return inventory.stock(id);
    }

    @GetMapping(value = "/quotes/{id}", produces = MediaType.TEXT_PLAIN_VALUE)
    String quote(@PathVariable String id) throws IOException, InterruptedException {
        return inventory.quote(id);
    }

    @GetMapping(value = "/pilot/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    String stats() {
        return Probe.stats(registry.snapshot().toMap());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    String limited() {
        return "limited";
    }

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    String failed() {
        return "failed";
    }
}
