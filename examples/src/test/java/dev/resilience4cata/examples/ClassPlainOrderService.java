package dev.resilience4cata.examples;

/**
 * Implements an annotated interface but declares no annotation itself, so no subclass is generated: it gets
 * the interface's policies only through {@code Resilient.of(ClassOrderApi.class, impl, registry)}.
 */
public class ClassPlainOrderService implements ClassOrderApi {

    final Flaky flaky = new Flaky(1);

    @Override
    public String status(String id) {
        flaky.call();
        return "plain " + id;
    }

    @Override
    public String quote(String id) {
        return status(id);
    }
}
