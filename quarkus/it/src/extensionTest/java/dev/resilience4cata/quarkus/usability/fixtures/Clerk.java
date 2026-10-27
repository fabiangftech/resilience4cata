package dev.resilience4cata.quarkus.usability.fixtures;

import jakarta.inject.Singleton;

@Singleton
public class Clerk {

    public String sign(String entry) {
        return entry + " (signed)";
    }
}
