package dev.resilience4cata.examples.docs.installation;

/** Not annotated, so the processor generates nothing for it: {@code Resilient.of} fails with R4C-RT-003. */
public interface NotProcessed {
    String call();
}
