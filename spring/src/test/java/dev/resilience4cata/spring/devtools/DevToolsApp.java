package dev.resilience4cata.spring.devtools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The application, loaded by the test through a {@code RestartClassLoader}: so the test reaches it only by
 * reflection, and it answers with plain strings and numbers, which mean the same in every class loader.
 */
@SpringBootApplication(scanBasePackageClasses = DevToolsApp.class)
public class DevToolsApp {

    private static ConfigurableApplicationContext context;

    /** Starts the application as a DevTools restart does, calls its beans, and says what it saw. */
    public static Map<String, Object> start() throws Exception {
        ClassLoader loader = DevToolsApp.class.getClassLoader();
        SpringApplication application = new SpringApplication(new DefaultResourceLoader(loader), DevToolsApp.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        context = application.run("--spring.main.banner-mode=off", "--spring.devtools.restart.enabled=false");

        Map<String, Object> seen = new LinkedHashMap<>();
        Greeter greeter = context.getBean(Greeter.class);
        seen.put("greeterClass", greeter.getClass().getName());
        seen.put("greeterLoaderIsCurrent", greeter.getClass().getClassLoader() == loader);
        seen.put("greeterSuperclassIsCurrent", greeter.getClass().getSuperclass() == Greeter.class);
        seen.put("greet", greeter.greet());
        seen.put("greetAttempts", greeter.attempts.getAndSet(0));

        seen.put("greetLater", greeter.greetLater().get(10, TimeUnit.SECONDS));
        seen.put("secondAttemptLoaderIsCurrent", greeter.secondAttemptLoader == loader);

        Clock clock = context.getBean(Clock.class);
        seen.put("clockClass", clock.getClass().getName());
        seen.put("clockLoaderIsCurrent", clock.getClass().getClassLoader() == loader);
        seen.put("now", clock.now());
        seen.put("nowAttempts", FlakyClock.ATTEMPTS.get());
        return seen;
    }

    public static void stop() {
        context.close();
        context = null;
    }
}
