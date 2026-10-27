package dev.resilience4cata.spring.autoconfigure;

import org.mockito.Mockito;
import org.springframework.util.ClassUtils;

/**
 * Mockito spies, which are not wrapped. A test's {@code @MockitoSpyBean I spy} spies on the bean before this
 * integration would wrap it, and Spring's verification support then hands Mockito the object passed to
 * {@code verify}, which must be the spy: a wrapper around it fails. So a spied bean is injected as the spy, without
 * the interface's policies, as a {@code @MockitoBean} mock is. Mockito is read only when it is on the class path.
 */
final class Spies {

    private static final boolean MOCKITO = ClassUtils.isPresent("org.mockito.Mockito", Spies.class.getClassLoader());

    private Spies() {
    }

    static boolean isSpy(Object bean) {
        return MOCKITO && Mockito.mockingDetails(bean).isSpy();
    }
}
