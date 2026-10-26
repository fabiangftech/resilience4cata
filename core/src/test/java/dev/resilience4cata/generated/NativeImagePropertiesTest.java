package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The GraalVM Native Image options that core ships: the classes whose initialization starts a background
 * thread are initialized at run time. A renamed or removed holder would make the native build fail, so every
 * listed class must exist.
 */
class NativeImagePropertiesTest {

    private static final String RESOURCE = "META-INF/native-image/dev.resilience4cata/resilience4cata-core/native-image.properties";
    private static final String OPTION = "--initialize-at-run-time=";

    @Test
    void initializesTheThreadStartingHoldersAtRunTime() throws IOException, ClassNotFoundException {
        Properties properties = new Properties();
        try (InputStream in = Watchdog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            properties.load(in);
        }
        List<String> args = Arrays.asList(properties.getProperty("Args").trim().split("\\s+"));
        assertThat(args).hasSize(1);
        assertThat(args.get(0)).startsWith(OPTION);
        List<String> classes = Arrays.asList(args.get(0).substring(OPTION.length()).split(","));

        assertThat(classes).containsExactly(Watchdog.class.getName() + "$Shared", TimeoutTimer.class.getName() + "$Shared",
                AsyncSupport.class.getName() + "$Timer");
        for (String name : classes) {
            Class<?> holder = Class.forName(name, false, Watchdog.class.getClassLoader());
            assertThat(holder.getPackageName()).isEqualTo(Watchdog.class.getPackageName());
        }
    }
}
