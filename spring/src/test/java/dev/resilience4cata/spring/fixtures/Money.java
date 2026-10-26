package dev.resilience4cata.spring.fixtures;

/** A value with two beans of this type in the context, told apart by {@code @Qualifier}. */
public record Money(String currency) {
}
