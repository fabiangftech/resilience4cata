package dev.resilience4cata.quarkus.guide;

import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands for a pager. */
@Singleton
public class Alerts {

    public final List<String> sent = new CopyOnWriteArrayList<>();

    public void send(String message) {
        sent.add(message);
    }
}
