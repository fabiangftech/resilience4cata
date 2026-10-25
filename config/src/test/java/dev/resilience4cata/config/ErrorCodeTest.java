package dev.resilience4cata.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The configuration error codes are unique and documented in both languages. */
class ErrorCodeTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^### (R4C-[A-Z]+-\\d{3})$");

    private static List<String> codes() throws IllegalAccessException {
        List<String> codes = new ArrayList<>();
        for (Field field : ProblemException.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                codes.add((String) field.get(null));
            }
        }
        return codes;
    }

    @Test
    void codesAreUniqueAndWellFormed() throws IllegalAccessException {
        assertThat(codes()).hasSize(13).doesNotHaveDuplicates().allMatch(c -> c.matches("R4C-CFG-\\d{3}"));
    }

    @Test
    void everyCodeIsDocumentedInEnglishAndSpanish() throws IOException, IllegalAccessException {
        for (String language : List.of("en", "es")) {
            String text = Files.readString(Path.of("..", "docs", language, "errors.md"));
            Set<String> documented = HEADING.matcher(text).results().map(m -> m.group(1)).collect(Collectors.toSet());
            assertThat(documented).as(language).containsAll(codes());
        }
    }
}
