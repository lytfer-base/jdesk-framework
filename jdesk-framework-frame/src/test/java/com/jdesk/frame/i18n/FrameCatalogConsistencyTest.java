package com.jdesk.frame.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the frame message catalogs: each namespace has en, zh_CN and ja files with the same keys, every key has the
 * same placeholders as in en, and every message is well formed for {@link com.jdesk.core.i18n.MessageFormatter}.
 */
class FrameCatalogConsistencyTest {
    private static final Path CATALOG_DIR = Path.of("src/main/resources/jdesk/i18n");
    private static final List<String> SUFFIXES = List.of("en", "zh_CN", "ja");
    private static final String SOURCE_SUFFIX = "en";
    private static final Pattern FILE_NAME = Pattern.compile("([a-z][A-Za-z0-9]*)_(en|zh_CN|ja)\\.properties");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    private static final Pattern LITERAL = Pattern.compile("\\{'[^']*'\\}");
    private static final Pattern ESCAPED_NON_ASCII =
            Pattern.compile(Pattern.quote(String.valueOf((char) 92)) + "u(?!00[0-7][0-9a-fA-F])[0-9a-fA-F]{4}");
    private static final char BOM = (char) 0xFEFF;

    @Test
    void catalogsAgreeAcrossLanguages() throws IOException {
        Map<String, Map<String, Catalog>> namespaces = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(CATALOG_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".properties")).sorted().toList()) {
                String name = file.getFileName().toString();
                Matcher match = FILE_NAME.matcher(name);
                if (!match.matches()) {
                    problems.add("bad file name: " + name);
                    continue;
                }
                namespaces.computeIfAbsent(match.group(1), namespace -> new TreeMap<>())
                        .put(match.group(2), Catalog.read(file));
            }
        }
        assertFalse(namespaces.isEmpty(), () -> "No catalogs found under " + CATALOG_DIR.toAbsolutePath());
        namespaces.forEach((namespace, bySuffix) -> problems.addAll(namespaceProblems(namespace, bySuffix)));
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void tokensCatchMalformedMessages() {
        assertEquals(Set.of("app", "file"), Tokens.of("Open {file} in {app}").placeholders());
        assertTrue(Tokens.of("Mail {'@'} support, {count} item | {count} items").errors().isEmpty());
        assertEquals(1, Tokens.of("{count} item | {count} items").pipes());
        assertEquals(1, Tokens.of("Mail @ support").errors().size());
        assertEquals(1, Tokens.of("Open {file").errors().size());
        assertEquals(1, Tokens.of("Open file}").errors().size());
    }

    private static List<String> namespaceProblems(String namespace, Map<String, Catalog> bySuffix) {
        List<String> problems = new ArrayList<>();
        for (String suffix : SUFFIXES) {
            if (!bySuffix.containsKey(suffix)) {
                problems.add(namespace + ": missing the " + suffix + " file");
            }
        }
        Catalog source = bySuffix.get(SOURCE_SUFFIX);
        if (source == null) {
            return problems;
        }
        bySuffix.forEach((suffix, catalog) -> {
            String file = namespace + "_" + suffix + ".properties";
            problems.addAll(catalog.formatProblems(file));
            for (String key : source.messages().keySet()) {
                if (!catalog.messages().containsKey(key)) {
                    problems.add(file + ": missing key " + key);
                }
            }
            catalog.messages().forEach((key, value) -> {
                if (!source.messages().containsKey(key)) {
                    problems.add(file + ": extra key " + key);
                    return;
                }
                problems.addAll(messageProblems(file, namespace, suffix, key, value, source.messages().get(key)));
            });
        });
        for (String key : source.messages().keySet()) {
            for (String other : source.messages().keySet()) {
                if (other.startsWith(key + ".")) {
                    problems.add(namespace + ": " + key + " is a prefix of " + other + "; nested message stores can't hold both");
                }
            }
        }
        return problems;
    }

    private static List<String> messageProblems(String file, String namespace, String suffix, String key, String value,
                                                String sourceValue) {
        List<String> problems = new ArrayList<>();
        if (!KEY.matcher(key).matches() || !key.startsWith(namespace + ".")) {
            problems.add(file + ": key " + key + " must look like " + namespace + ".name");
        }
        if (value.isBlank()) {
            problems.add(file + ": " + key + " is empty");
        }
        Tokens tokens = Tokens.of(value);
        tokens.errors().forEach(error -> problems.add(file + ": " + key + ": " + error));
        if (!suffix.equals("en") && tokens.pipes() > 0) {
            problems.add(file + ": " + key + ": only en may use | for plural forms");
        } else if (tokens.pipes() > 1) {
            problems.add(file + ": " + key + ": at most two plural forms");
        }
        Set<String> expected = Tokens.of(sourceValue).placeholders();
        if (!tokens.placeholders().equals(expected)) {
            problems.add(file + ": " + key + ": placeholders " + tokens.placeholders() + " differ from en " + expected);
        }
        return problems;
    }

    private record Catalog(String text, Map<String, String> messages, Set<String> duplicates) {
        static Catalog read(Path file) throws IOException {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            RecordingProperties properties = new RecordingProperties();
            properties.load(new StringReader(text));
            Map<String, String> messages = new TreeMap<>();
            for (String key : properties.stringPropertyNames()) {
                messages.put(key, properties.getProperty(key));
            }
            return new Catalog(text, messages, properties.duplicates);
        }

        List<String> formatProblems(String file) {
            List<String> problems = new ArrayList<>();
            if (!text.isEmpty() && text.charAt(0) == BOM) {
                problems.add(file + ": starts with a BOM; save it as UTF-8 without one");
            }
            if (ESCAPED_NON_ASCII.matcher(text).find()) {
                problems.add(file + ": write non-ASCII text as UTF-8, not as escapes");
            }
            duplicates.forEach(key -> problems.add(file + ": duplicate key " + key));
            return problems;
        }
    }

    private record Tokens(Set<String> placeholders, int pipes, List<String> errors) {
        static Tokens of(String value) {
            Set<String> placeholders = new TreeSet<>();
            List<String> errors = new ArrayList<>();
            int pipes = 0;
            int i = 0;
            while (i < value.length()) {
                char c = value.charAt(i);
                if (c == '{') {
                    Matcher literal = LITERAL.matcher(value).region(i, value.length());
                    if (literal.lookingAt()) {
                        i = literal.end();
                        continue;
                    }
                    Matcher placeholder = PLACEHOLDER.matcher(value).region(i, value.length());
                    if (placeholder.lookingAt()) {
                        placeholders.add(placeholder.group(1));
                        i = placeholder.end();
                        continue;
                    }
                    errors.add("'{' at " + (i + 1) + " is neither a placeholder nor a literal");
                } else if (c == '}') {
                    errors.add("stray '}' at " + (i + 1));
                } else if (c == '@' || c == '$') {
                    errors.add("write " + c + " as {'" + c + "'}");
                } else if (c == '|') {
                    pipes++;
                }
                i++;
            }
            return new Tokens(placeholders, pipes, errors);
        }
    }

    private static final class RecordingProperties extends Properties {
        private final Set<String> duplicates = new TreeSet<>();

        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) {
                duplicates.add(String.valueOf(key));
            }
            return super.put(key, value);
        }
    }
}
