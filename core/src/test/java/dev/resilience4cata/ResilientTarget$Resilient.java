package dev.resilience4cata;

/** Hand-written stand-in for the subclass the processor would generate for {@link ResilientTarget}. */
public final class ResilientTarget$Resilient extends ResilientTarget {

    final ResilienceRegistry registry;

    public ResilientTarget$Resilient(ResilienceRegistry registry) {
        this.registry = registry;
    }

    public ResilientTarget$Resilient(ResilienceRegistry registry, String p0) {
        super(p0);
        this.registry = registry;
    }

    public ResilientTarget$Resilient(ResilienceRegistry registry, Integer p0) {
        super(p0);
        this.registry = registry;
    }

    public ResilientTarget$Resilient(ResilienceRegistry registry, int p0, long p1) {
        super(p0, p1);
        this.registry = registry;
    }

    public ResilientTarget$Resilient(ResilienceRegistry registry, boolean p0, String... p1) {
        super(p0, p1);
        this.registry = registry;
    }

    public ResilientTarget$Resilient(ResilienceRegistry registry, String p0, String... p1) {
        super(p0, p1);
        this.registry = registry;
    }

    public ResilientTarget$Resilient(ResilienceRegistry registry, double p0) {
        super(p0);
        this.registry = registry;
    }
}
