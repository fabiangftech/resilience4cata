package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class ResilientCreateTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();

    private ResilientTarget create(Object... arguments) {
        ResilientTarget target = Resilient.create(ResilientTarget.class, registry, arguments);
        assertThat(target).isInstanceOf(ResilientTarget$Resilient.class);
        assertThat(((ResilientTarget$Resilient) target).registry).isSameAs(registry);
        return target;
    }

    @Test
    void callsTheConstructorThatMatchesTheArguments() {
        assertThat(create().description).isEqualTo("none");
        assertThat(create("a").description).isEqualTo("string a");
        assertThat(create(7).description).isEqualTo("integer 7");
        // primitive parameters take their wrapper
        assertThat(create(1, 2L).description).isEqualTo("ints 1 2");
    }

    @Test
    void expandsVarargsLikeTheCompiler() {
        assertThat(create(true).description).isEqualTo("varargs true []");
        assertThat(create(true, "a", "b").description).isEqualTo("varargs true [a, b]");
        assertThat(create(true, new String[] {"c"}).description).isEqualTo("varargs true [c]");
        assertThat(create(true, null).description).isEqualTo("varargs true null");
        // a constructor that fits without expansion wins, as in Java: (String) rather than (String, String...)
        assertThat(create("a").description).isEqualTo("string a");
        assertThat(create("a", "b").description).isEqualTo("strings a [b]");
    }

    @Test
    void rejectsArgumentsThatMatchNoConstructor() {
        assertThatIllegalArgumentException().isThrownBy(() -> create(1, 2))
                .withMessageContaining("No constructor of dev.resilience4cata.ResilientTarget accepts the arguments"
                        + " (Integer, Integer)")
                .withMessageContaining("(int, long)")
                .withMessageContaining("(boolean, String...)")
                .withMessageContaining("new ResilientTarget$Resilient(registry, ...)");
        // null never matches a primitive parameter
        assertThatIllegalArgumentException().isThrownBy(() -> create(null, 2L))
                .withMessageContaining("accepts the arguments (null, Long)");
        assertThatIllegalArgumentException().isThrownBy(() -> create(true, "a", 3))
                .withMessageContaining("No constructor");
    }

    @Test
    void rejectsArgumentsThatMatchMoreThanOneConstructor() {
        assertThatIllegalArgumentException().isThrownBy(() -> create((Object) null))
                .withMessageContaining("More than one constructor of dev.resilience4cata.ResilientTarget accepts the"
                        + " arguments (null): ")
                .withMessageContaining("(String)")
                .withMessageContaining("(Integer)")
                .withMessageNotContaining("(int, long)")
                .withMessageContaining("call the generated constructor directly, which the compiler checks:"
                        + " new ResilientTarget$Resilient(registry, ...)");
    }

    @Test
    void propagatesExceptionsFromTheConstructor() {
        assertThatIllegalArgumentException().isThrownBy(() -> create(1.5)).withMessage("constructor failed with 1.5");
    }

    @Test
    void failsFastWhenTheSubclassWasNotGenerated() {
        assertThatIllegalStateException()
                .isThrownBy(() -> Resilient.create(ResilientTarget.Unprocessed.class, registry))
                .withMessageContaining("No generated class dev.resilience4cata.ResilientTarget$Unprocessed$Resilient")
                .withMessageContaining("annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'")
                .withMessageNotContaining("native image");
    }

    @Test
    void inANativeImageTheMessageAlsoPointsAtTheReachabilityMetadata() {
        String generated = "dev.resilience4cata.ResilientTarget$Unprocessed$Resilient";
        String plain = Resilient.notGenerated(ResilientTarget.Unprocessed.class, generated, false);
        String nativeImage = Resilient.notGenerated(ResilientTarget.Unprocessed.class, generated, true);

        assertThat(plain).startsWith("[R4C-RT-003] No generated class " + generated + " found.")
                .endsWith("annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'");
        assertThat(nativeImage).isEqualTo(plain + "\nIn a native image, also check that the image build's class path"
                + " includes the reachability metadata the processor writes next to the class files,"
                + " META-INF/native-image/dev.resilience4cata.generated/" + generated + "/reachability-metadata.json,"
                + " and that no build step strips it.");
    }

    @Test
    void rejectsTypesThatCannotHaveAGeneratedSubclass() {
        class Local {
        }
        // java:S2133: the test needs an anonymous class, not Object itself
        @SuppressWarnings("java:S2133")
        Object anonymous = new Object() {
        };
        assertThatIllegalArgumentException().isThrownBy(() -> Resilient.create(Runnable.class, registry))
                .withMessageContaining("is an interface. Use Resilient.of(Runnable.class, implementation, registry)");
        for (Class<?> type : new Class<?>[] {ResilientTarget.Final.class, ResilientTarget.Abstract.class, Local.class,
                anonymous.getClass(), Thread.State.class, int.class}) {
            assertThatIllegalArgumentException().as(type.getName()).isThrownBy(() -> Resilient.create(type, registry))
                    .withMessageContaining("cannot be extended by a generated subclass");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> Resilient.of(ResilientTarget.class, new ResilientTarget(),
                registry)).withMessageContaining("For an annotated class, use Resilient.create(ResilientTarget.class, registry)");
    }
}
