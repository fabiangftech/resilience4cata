package dev.resilience4cata.quarkus.runtime;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import io.quarkus.arc.BeanCreator;
import io.quarkus.arc.SyntheticCreationalContext;
import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * Creates the synthetic bean that stands for a bean of an annotated interface: the generated wrapper
 * {@code I$Resilient} around the bean, through {@link Resilient#of}. The deployment module registers one such bean
 * per protected bean, with the parameters {@value #INTERFACE} (the annotated interface), {@value #DELEGATE} (the
 * bean's class, or the interface for a REST client) and {@value #QUALIFIERS} (the qualifiers that select the
 * delegate), and declares the matching injection points, so ArC validates them at build time.
 */
public final class ResilientWrapperCreator implements BeanCreator<Object> {

    /** Parameter: the annotated interface. */
    public static final String INTERFACE = "interface";
    /** Parameter: the type the delegate is injected as. */
    public static final String DELEGATE = "delegate";
    /** Parameter: the qualifiers the delegate is injected with. */
    public static final String QUALIFIERS = "qualifiers";

    /** Created by ArC. */
    public ResilientWrapperCreator() {
    }

    @Override
    public Object create(SyntheticCreationalContext<Object> context) {
        Map<String, Object> params = context.getParams();
        Class<?> type = (Class<?>) params.get(INTERFACE);
        Class<?> delegateType = (Class<?>) params.get(DELEGATE);
        Annotation[] qualifiers = qualifiers(params.get(QUALIFIERS));
        Object delegate = context.getInjectedReference(delegateType, qualifiers);
        ResilienceRegistry registry = context.getInjectedReference(ResilienceRegistry.class);
        return wrap(type, delegate, registry);
    }

    private static Annotation[] qualifiers(Object param) {
        if (param instanceof Annotation[] annotations) {
            return annotations;
        }
        return param instanceof Object[] values && values.length > 0
                ? java.util.Arrays.copyOf(values, values.length, Annotation[].class) : new Annotation[0];
    }

    private static <T> T wrap(Class<T> type, Object delegate, ResilienceRegistry registry) {
        return Resilient.of(type, type.cast(delegate), registry);
    }
}
