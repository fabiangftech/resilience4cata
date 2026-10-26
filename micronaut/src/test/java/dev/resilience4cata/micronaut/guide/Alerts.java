package dev.resilience4cata.micronaut.guide;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands for a pager or a chat channel. */
@Singleton
@Requires(env = "guide")
public class Alerts {

    public final List<String> sent = new CopyOnWriteArrayList<>();

    public void send(String message) {
        sent.add(message);
    }
}
