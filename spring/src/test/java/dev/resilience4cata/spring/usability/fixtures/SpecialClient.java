package dev.resilience4cata.spring.usability.fixtures;

import dev.resilience4cata.annotation.Timeout;
import org.springframework.stereotype.Component;

/** Extends an annotated base class and adds a policy of its own, so it is processed itself. */
@Component
public class SpecialClient extends BaseClient {

    @Timeout("1s")
    public String special(String id) {
        return "special-" + id;
    }
}
