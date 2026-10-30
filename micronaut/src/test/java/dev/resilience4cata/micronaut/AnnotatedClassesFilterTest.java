package dev.resilience4cata.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** How {@link AnnotatedClassesFilter} reads the names of Micronaut's definitions. */
class AnnotatedClassesFilterTest {

    @Test
    void aDefinitionNamesItsClass() {
        assertThat(AnnotatedClassesFilter.definedClass("p.$Ledger$Definition")).isEqualTo("p.Ledger");
        assertThat(AnnotatedClassesFilter.definedClass("p.$Ledger$Definition$Intercepted$Definition")).isEqualTo("p.Ledger");
        assertThat(AnnotatedClassesFilter.definedClass("p.$Outer$Inner$Definition")).isEqualTo("p.Outer$Inner");
    }

    @Test
    void anAdapterAdaptsTheClassBeforeItsInterfaceAndMethod() {
        assertThat(AnnotatedClassesFilter.adaptedClass("p.Events$ApplicationEventListener$on1$Intercepted"))
                .isEqualTo("p.Events");
        assertThat(AnnotatedClassesFilter.adaptedClass("p.Outer$Inner$ApplicationEventListener$on1$Intercepted"))
                .as("a nested class's adapter is not its outer class's").isEqualTo("p.Outer$Inner");
        assertThat(AnnotatedClassesFilter.adaptedClass("p.Events$on1$Intercepted")).isNull();
        assertThat(AnnotatedClassesFilter.adaptedClass("p.Events$ApplicationEventListener$on1")).isNull();
    }
}
