package dev.resilience4cata.spring.devtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.devtools.restart.classloader.RestartClassLoader;

/**
 * Spring Boot DevTools restarts an application by closing its context and starting it again in a new
 * {@code RestartClassLoader}, which loads the application's own classes (the generated ones included) again, while
 * the libraries stay in the base class loader. This test does the same twice, in a new thread whose context class
 * loader is the restart loader, as DevTools' restarted main thread. After each start the beans must be the generated
 * classes of that loader, never those of the base loader or of the previous start, and work that the library runs
 * later (a retry's next attempt) must run with that loader too.
 */
class DevToolsRestartTest {

    private static final String APP = "dev.resilience4cata.spring.devtools.DevToolsApp";

    @Test
    void everyRestartGetsTheGeneratedClassesOfItsOwnClassLoader() throws Exception {
        URL classes = DevToolsRestartTest.class.getProtectionDomain().getCodeSource().getLocation();
        ClassLoader base = DevToolsRestartTest.class.getClassLoader();

        ClassLoader previous = null;
        for (int restart = 0; restart < 2; restart++) {
            RestartClassLoader loader = new RestartClassLoader(base, new URL[] {classes});
            Map<String, Object> seen = startIn(loader);

            assertThat(seen).as("start " + (restart + 1))
                    .containsEntry("greeterClass", "dev.resilience4cata.spring.devtools.Greeter$Resilient")
                    .containsEntry("greeterLoaderIsCurrent", true)
                    .containsEntry("greeterSuperclassIsCurrent", true)
                    .containsEntry("greet", "hello")
                    .containsEntry("greetAttempts", 3)
                    .containsEntry("greetLater", "hello later")
                    .containsEntry("secondAttemptLoaderIsCurrent", true)
                    .containsEntry("clockClass", "dev.resilience4cata.spring.devtools.Clock$Resilient")
                    .containsEntry("clockLoaderIsCurrent", true)
                    .containsEntry("now", "noon")
                    .containsEntry("nowAttempts", 3);
            assertThat(loader).isNotSameAs(previous);
            previous = loader;
        }
    }

    /** Starts the application in {@code loader}, on a new thread whose context class loader it is, then stops it. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> startIn(ClassLoader loader) throws Exception {
        Class<?> app = loader.loadClass(APP);
        assertThat(app.getClassLoader()).as("the application's classes come from the restart loader").isSameAs(loader);
        AtomicReference<Object> result = new AtomicReference<>();
        Thread restartedMain = new Thread(() -> {
            try {
                result.set(app.getMethod("start").invoke(null));
                app.getMethod("stop").invoke(null);
            } catch (ReflectiveOperationException e) {
                result.set(e.getCause() == null ? e : e.getCause());
            }
        }, "restartedMain");
        restartedMain.setContextClassLoader(loader);
        restartedMain.start();
        restartedMain.join();
        if (result.get() instanceof Throwable failure) {
            throw new AssertionError("the application failed in " + loader, failure);
        }
        return (Map<String, Object>) result.get();
    }
}
