package dev.resilience4cata.examples.docs.classifiers;

/** A minimal HTTP-like response for the classifier examples. */
public record Response(int status, String body) {
}
