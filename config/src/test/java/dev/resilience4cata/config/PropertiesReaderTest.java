package dev.resilience4cata.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PropertiesReaderTest {

    @Test
    void keepsLineNumbersAndDuplicates() {
        List<PropertiesReader.Entry> entries = PropertiesReader.read("""
                # comment
                a=1

                ! other comment
                b = 2
                a:3
                """);
        assertThat(entries).containsExactly(
                new PropertiesReader.Entry("a", "1", 2),
                new PropertiesReader.Entry("b", "2", 5),
                new PropertiesReader.Entry("a", "3", 6));
    }

    @Test
    void continuationLinesKeepTheFirstLineNumber() {
        List<PropertiesReader.Entry> entries = PropertiesReader.read("a=one, \\\n    two\nb=x\r\nc=y\rd=z");
        assertThat(entries).containsExactly(
                new PropertiesReader.Entry("a", "one, two", 1),
                new PropertiesReader.Entry("b", "x", 3),
                new PropertiesReader.Entry("c", "y", 4),
                new PropertiesReader.Entry("d", "z", 5));
    }

    @Test
    void rejectsMalformedUnicodeEscape() {
        assertThatIllegalArgumentException().isThrownBy(() -> PropertiesReader.read("a=1\nb=\\u12"))
                .withMessageContaining("line 2");
        assertThatIllegalArgumentException().isThrownBy(() -> PropertiesReader.read("b=\\u12zz"))
                .withMessageContaining("line 1");
    }

    /** Same keys and values as {@link Properties#load(java.io.Reader)}. */
    @ParameterizedTest
    @ValueSource(strings = {
            "a=1\nb=2",
            "  a  =  1  \n",
            "a:1\nb 2\nc\t3\nd\f4",
            "a\\ b=c\\=d",
            "a=\\t\\n\\r\\f\\u0041\\\\\\q",
            "a=1\\\n  2\\\n  3",
            "a=ends with backslash\\\\",
            "a=\\",
            "key only",
            "=value only",
            "a==b",
            "a=:b",
            "# c\n! c\n   # indented comment\na=1",
            "a=1 \\\n# not a comment\nb=2",
            "a=x\r\nb=y\rc=z",
            "a=é ü",
            "a\\:b:c",
            "a=1\\"})
    void matchesJavaUtilProperties(String text) throws IOException {
        Properties expected = new Properties();
        expected.load(new StringReader(text));
        Map<String, String> actual = new LinkedHashMap<>();
        for (PropertiesReader.Entry entry : PropertiesReader.read(text)) {
            actual.put(entry.key(), entry.value());
        }
        Map<String, String> expectedMap = new LinkedHashMap<>();
        for (String name : expected.stringPropertyNames()) {
            expectedMap.put(name, expected.getProperty(name));
        }
        assertThat(actual).isEqualTo(expectedMap);
    }
}
