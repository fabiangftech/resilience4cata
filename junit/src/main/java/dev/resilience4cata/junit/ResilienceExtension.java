package dev.resilience4cata.junit;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;

/**
 * The JUnit Jupiter extension behind {@link ResilienceTest}: resolves {@link ManualClock} and
 * {@link ResilienceRegistry} parameters of test methods and of their {@code @BeforeEach} and
 * {@code @AfterEach} methods, with one fresh, consistent pair per test (the registry follows the clock).
 * Register it with {@link ResilienceTest} (which also holds the settings), with
 * {@code @ExtendWith(ResilienceExtension.class)} (default settings), or with {@code @RegisterExtension}.
 *
 * <p>The clock is created when a test first asks for the clock or the registry; the registry, when it
 * first asks for the registry (see {@link ConfigureRegistry}). Both live until the end of that test.
 * Constructors and {@code @BeforeAll} methods cannot receive them, since they serve more than one test.
 */
public final class ResilienceExtension implements ParameterResolver, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(ResilienceExtension.class);
    private static final String KEY = "state";

    /** Creates the extension; JUnit calls this when it registers it. */
    public ResilienceExtension() {
        // Stateless: each test's clock and registry live in its ExtensionContext store.
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == ManualClock.class || type == ResilienceRegistry.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        if (context.getTestMethod().isEmpty() || context.getTestInstance().isEmpty()) {
            throw new ParameterResolutionException(ErrorCode.NOT_PER_TEST.prefix() + "ResilienceExtension cannot inject " + parameter.getParameter().getType().getSimpleName()
                    + " into " + parameter.getDeclaringExecutable() + ": the clock and the registry are created for each test,"
                    + " so take them as parameters of the test method or of a @BeforeEach method instead of a constructor or a @BeforeAll method");
        }
        PerTest state = state(context);
        return parameter.getParameter().getType() == ManualClock.class ? state.clock : state.registryFor(context);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        PerTest state = context.getStore(NAMESPACE).get(KEY, PerTest.class);
        if (state == null || !state.settings.failOnPendingTasks() || context.getExecutionException().isPresent()) {
            return;
        }
        int pending = state.clock.pendingTasks();
        if (pending > 0) {
            throw new AssertionError(ErrorCode.PENDING_TASKS.prefix() + "The test ended with " + pending + " task(s) still pending on its clock (" + state.clock
                    + "): an async retry, wait or timeout never ran. Advance the clock past them, for instance with"
                    + " clock.advanceUntilIdle(), or set @ResilienceTest(failOnPendingTasks = false)");
        }
    }

    private static PerTest state(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        PerTest state = store.get(KEY, PerTest.class);
        if (state == null) { // one test runs on one thread at a time
            state = new PerTest(settings(context));
            store.put(KEY, state);
        }
        return state;
    }

    /** The closest {@link ResilienceTest}: the method's, the class's, then the enclosing classes'. */
    private static Settings settings(ExtensionContext context) {
        Optional<ResilienceTest> found = find(context.getTestMethod().orElse(null));
        for (Class<?> type = context.getTestClass().orElse(null); found.isEmpty() && type != null; type = enclosing(type)) {
            found = find(type);
        }
        return found.map(a -> new Settings(a.seed(), a.startNanos(), a.failOnPendingTasks()))
                .orElse(Settings.DEFAULTS);
    }

    private static Optional<ResilienceTest> find(@Nullable AnnotatedElement element) {
        return element == null ? Optional.empty() : AnnotationSupport.findAnnotation(element, ResilienceTest.class);
    }

    private static @Nullable Class<?> enclosing(Class<?> type) {
        return Modifier.isStatic(type.getModifiers()) ? null : type.getEnclosingClass();
    }

    private record Settings(long seed, long startNanos, boolean failOnPendingTasks) {
        static final Settings DEFAULTS = new Settings(ResilienceTesting.DEFAULT_SEED, 0L, false);
    }

    /** The clock and registry of one test. */
    private static final class PerTest {
        final Settings settings;
        final ManualClock clock;
        @Nullable ResilienceRegistry registry;

        PerTest(Settings settings) {
            this.settings = settings;
            this.clock = new ManualClock(settings.startNanos());
        }

        ResilienceRegistry registryFor(ExtensionContext context) {
            ResilienceRegistry built = registry;
            if (built == null) {
                ResilienceRegistry.Builder builder = ResilienceTesting.registry(clock, settings.seed());
                for (Object instance : context.getRequiredTestInstances().getAllInstances()) { // outermost first
                    configure(instance, builder);
                }
                built = builder.build();
                registry = built;
            }
            return built;
        }

        private static void configure(Object instance, ResilienceRegistry.Builder builder) {
            for (Method method : AnnotationSupport.findAnnotatedMethods(instance.getClass(), ConfigureRegistry.class,
                    HierarchyTraversalMode.TOP_DOWN)) {
                if (method.getReturnType() != void.class || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != ResilienceRegistry.Builder.class
                        || Modifier.isPrivate(method.getModifiers())) {
                    throw new ExtensionConfigurationException(ErrorCode.BAD_CONFIGURE_METHOD.prefix() + "@ConfigureRegistry method " + method
                            + " must be a non-private void method with one ResilienceRegistry.Builder parameter, for example"
                            + " 'void configure(ResilienceRegistry.Builder builder)'");
                }
                ReflectionSupport.invokeMethod(method, Modifier.isStatic(method.getModifiers()) ? null : instance, builder);
            }
        }
    }
}
