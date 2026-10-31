package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.FallbackContext;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import org.junit.jupiter.api.Test;

class FallbackPolicyTest {

    @Test
    void appliesToApplyOnTypesUnlessASkipOnTypeMatches() {
        FallbackPolicy policy = new FallbackPolicy("id", List.of(IOException.class, IllegalStateException.class),
                List.of(FileNotFoundException.class));

        assertThat(policy.handles(new IOException())).isTrue();
        assertThat(policy.handles(new IllegalStateException())).isTrue();
        assertThat(policy.handles(new FileNotFoundException())).as("skipOn wins").isFalse();
        assertThat(policy.handles(new IllegalArgumentException())).as("not in applyOn").isFalse();
    }

    @Test
    void withSiblingTypesOnlyTheFailuresASiblingTakesAreHandled() {
        FallbackPolicy policy = new FallbackPolicy("id", List.of(Throwable.class), List.of(FileNotFoundException.class),
                List.of(IOException.class, IllegalStateException.class), PolicyEnvironment.DEFAULT);

        assertThat(policy.handles(new IOException())).isTrue();
        assertThat(policy.handles(new IllegalStateException())).isTrue();
        assertThat(policy.handles(new FileNotFoundException())).as("skipOn still wins").isFalse();
        assertThat(policy.handles(new IllegalArgumentException())).as("no sibling takes it").isFalse();
        assertThat(policy.handles(new AssertionError())).as("no sibling takes it").isFalse();
    }

    @Test
    void theContextExposesTheCallAndBoxedPrimitiveArguments() {
        FallbackPolicy policy = new FallbackPolicy("demo.Client#find(java.lang.String,int)", List.of(Throwable.class), List.of());
        IOException failure = new IOException("down");
        FallbackContext context = policy.context(failure, 3, new Object[] {"a", 7, null});

        assertThat(context.failure()).isSameAs(failure);
        assertThat(context.attempts()).isEqualTo(3);
        assertThat(context.id()).isEqualTo("demo.Client#find(java.lang.String,int)");
        assertThat(context.argumentCount()).isEqualTo(3);
        assertThat(context.argument(0, String.class)).isEqualTo("a");
        assertThat(context.argument(1, int.class)).isEqualTo(7);
        assertThat(context.argument(1, Integer.class)).isEqualTo(7);
        assertThat(context.argument(2, String.class)).isNull();
        assertThatThrownBy(() -> context.argument(0, Integer.class)).isInstanceOf(ClassCastException.class);
        assertThatThrownBy(() -> context.argument(3, Object.class)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void uncheckedPassesRuntimeExceptionsThrowsErrorsAndWrapsCheckedOnes() {
        IllegalStateException runtime = new IllegalStateException();
        assertThat(FallbackPolicy.unchecked(runtime)).isSameAs(runtime);

        AssertionError error = new AssertionError();
        assertThatThrownBy(() -> FallbackPolicy.unchecked(error)).isSameAs(error);

        IOException checked = new IOException();
        assertThat(FallbackPolicy.unchecked(checked)).isInstanceOf(UndeclaredThrowableException.class).hasCause(checked);
    }
}
