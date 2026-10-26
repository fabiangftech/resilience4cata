package dev.resilience4cata.quarkus.deployment.fixtures;

import jakarta.inject.Singleton;

@Singleton
public class Helper {

    public String name() {
        return "helper";
    }
}
