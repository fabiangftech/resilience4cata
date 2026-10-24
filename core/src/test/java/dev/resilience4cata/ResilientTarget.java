package dev.resilience4cata;

import java.util.Arrays;

/** A class with overloaded constructors, standing in for an annotated class in {@link ResilientCreateTest}. */
public class ResilientTarget {

    final String description;

    public ResilientTarget() {
        this.description = "none";
    }

    public ResilientTarget(String name) {
        this.description = "string " + name;
    }

    public ResilientTarget(Integer number) {
        this.description = "integer " + number;
    }

    public ResilientTarget(int number, long other) {
        this.description = "ints " + number + " " + other;
    }

    public ResilientTarget(boolean flag, String... names) {
        this.description = "varargs " + flag + " " + Arrays.toString(names);
    }

    public ResilientTarget(String first, String... more) {
        this.description = "strings " + first + " " + Arrays.toString(more);
    }

    public ResilientTarget(double failure) {
        throw new IllegalArgumentException("constructor failed with " + failure);
    }

    /** A class with no generated subclass. */
    public static class Unprocessed {
    }

    /** Final classes cannot have a generated subclass. */
    public static final class Final {
    }

    /** Abstract classes cannot have a generated subclass. */
    public abstract static class Abstract {
    }
}
