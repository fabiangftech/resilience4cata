package dev.resilience4cata.spring.guide;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** Stands for an alerting service. */
@Component
public class Alerts {

    final List<String> sent = new CopyOnWriteArrayList<>();

    public void send(String message) {
        sent.add(message);
    }
}
