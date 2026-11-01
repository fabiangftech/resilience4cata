package dev.resilience4cata.micronaut.scenarios;

/** A plain interface implemented by two annotated classes, told apart by qualifiers. */
public interface Notifier {

    String send(String message);
}
