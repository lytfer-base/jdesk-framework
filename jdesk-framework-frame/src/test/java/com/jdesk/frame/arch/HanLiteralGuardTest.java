package com.jdesk.frame.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps CJK text out of framework sources. Code outside comments in core and frame may only hold the exceptions listed
 * in {@code arch/han-literal-exceptions.txt}, because user-facing text goes through the i18n bundles; log calls may hold
 * neither CJK nor user content; comments must be English in every module, tests included.
 */
class HanLiteralGuardTest {
    private static final List<Path> ROOTS = List.of(
            Path.of("../jdesk-framework-core/src/main/java"),
            Path.of("src/main/java"));
    private static final List<Path> COMMENT_ROOTS = List.of(
            Path.of("../jdesk-framework-core/src/main/java"),
            Path.of("../jdesk-framework-core/src/test/java"),
            Path.of("src/main/java"),
            Path.of("src/test/java"),
            Path.of("../jdesk-framework-demo/src/main/java"),
            Path.of("../jdesk-framework-demo/src/test/java"),
            Path.of("../jdesk-framework-demo/demo-greeter/src/main/java"));
    private static final Pattern CJK = Pattern.compile(
            "[\\p{IsHan}\\u3000-\\u30FF\\uFF01-\\uFF0F\\uFF1A-\\uFF20\\uFF3B-\\uFF40\\uFF5B-\\uFF9F]");
    private static final Pattern LOG_CALL = Pattern.compile("\\b(?:LOG|LOGGER|log|logger)\\.(?:trace|debug|info|warn|error)\\(");
    private static final Pattern USER_CONTENT_PLACEHOLDER = Pattern.compile("\\b(?:title|body|content|query|keyword|prompt)=\\{}");

    @Test
    void hanOnlyAppearsInListedExceptions() throws IOException {
        Map<String, Integer> exceptions = readExceptions();
        Map<String, Integer> current = new TreeMap<>();
        List<String> logViolations = new ArrayList<>();
        int scanned = 0;
        for (Path root : ROOTS) {
            assertTrue(Files.isDirectory(root), () -> "Scan root is missing, so the guard would skip it: " + root.toAbsolutePath().normalize());
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt")).toList()) {
                    scanned++;
                    List<String> code = codeLines(Files.readString(path, StandardCharsets.UTF_8));
                    String name = Path.of("").toAbsolutePath().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    int count = 0;
                    for (String line : code) {
                        if (CJK.matcher(line).find()) {
                            count++;
                        }
                    }
                    if (count > 0) {
                        current.put(name, count);
                    }
                    logViolations.addAll(logCallViolations(name, String.join("\n", code)));
                }
            }
        }
        assertTrue(scanned > 100, "Too few source files were scanned; the guard may be broken");
        Set<String> files = new TreeSet<>(current.keySet());
        files.addAll(exceptions.keySet());
        List<String> changed = new ArrayList<>();
        for (String file : files) {
            int n = current.getOrDefault(file, 0);
            int allowed = exceptions.getOrDefault(file, 0);
            if (n > allowed) {
                changed.add(file + ": " + allowed + " -> " + n + "  <- hard-coded CJK text; move it to the i18n bundles");
            } else if (n < allowed) {
                changed.add(file + ": " + allowed + " -> " + n + "  <- lower this entry in han-literal-exceptions.txt (remove it at 0)");
            }
        }
        assertTrue(changed.isEmpty(), () -> String.join("\n", changed));
        assertTrue(logViolations.isEmpty(), () -> String.join("\n", logViolations));
    }

    @Test
    void commentsHaveNoCjk() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (Path root : COMMENT_ROOTS) {
            assertTrue(Files.isDirectory(root), () -> "Scan root is missing, so the guard would skip it: " + root.toAbsolutePath().normalize());
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt")).toList()) {
                    scanned++;
                    List<String> comments = lex(Files.readString(path, StandardCharsets.UTF_8)).comments();
                    String name = Path.of("").toAbsolutePath().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    for (int i = 0; i < comments.size(); i++) {
                        if (CJK.matcher(comments.get(i)).find()) {
                            violations.add(name + ":" + (i + 1) + ": " + comments.get(i).strip());
                        }
                    }
                }
            }
        }
        assertTrue(scanned > 300, "Too few source files were scanned; the guard may be broken");
        assertTrue(violations.isEmpty(), () -> "Write comments in English:\n" + String.join("\n", violations));
    }

    @Test
    void commentScanSkipsStringsAndTextBlocks() {
        String source = String.join("\n",
                "class A {",
                "    String s = \"// 中\";",
                "    String t = \"\"\"",
                "        /* 中 */",
                "        \"\"\";",
                "    int x; // 中",
                "    /* 中",
                "       中 */ int y;",
                "}");
        List<String> comments = lex(source).comments();
        List<Integer> flagged = new ArrayList<>();
        for (int i = 0; i < comments.size(); i++) {
            if (CJK.matcher(comments.get(i)).find()) {
                flagged.add(i + 1);
            }
        }
        assertEquals(List.of(6, 7, 8), flagged);
    }

    @Test
    void logCheckFollowsCallsAcrossLines() {
        String source = String.join("\n",
                "class A {",
                "    void f() {",
                "        LOG.warn(\"a.b id={}\"",
                "                + \" (中)\", id);",
                "        LOG.info(\"a.c id={}\" // (",
                "                + \" title={}\", id, title);",
                "        LOG.debug(\"a.d {} {}\", \")\",",
                "                \"中\");",
                "        LOG.info(\"a.e\"); // 中",
                "        LOG.info(\"a.f {}\", x);",
                "        LOG.info(\"a.g id={}：\", id);",
                "        String s = \"中\";",
                "    }",
                "}");
        List<String> reported = logCallViolations("A.java", String.join("\n", codeLines(source))).stream()
                .map(violation -> violation.substring(0, violation.indexOf(':', violation.indexOf(':') + 1)))
                .toList();
        assertEquals(List.of("A.java:3", "A.java:5", "A.java:7", "A.java:11"), reported);
    }

    /** Checks each log call for CJK text and user content; a call may span lines and is reported at its first line. */
    static List<String> logCallViolations(String name, String code) {
        List<String> violations = new ArrayList<>();
        Matcher call = LOG_CALL.matcher(code);
        int line = 1;
        int counted = 0;
        while (call.find()) {
            for (; counted < call.start(); counted++) {
                if (code.charAt(counted) == '\n') {
                    line++;
                }
            }
            String span = code.substring(call.start(), callEnd(code, call.end() - 1));
            String flat = span.replaceAll("\\s*\\n\\s*", " ").trim();
            if (CJK.matcher(span).find()) {
                violations.add(name + ":" + line + ": CJK text in a log call: " + flat);
            }
            if (USER_CONTENT_PLACEHOLDER.matcher(span).find()) {
                violations.add(name + ":" + line + ": user content in a log call: " + flat);
            }
        }
        return violations;
    }

    /** Returns the position after the parenthesis matching {@code open}, skipping string, text block and char literals. */
    private static int callEnd(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (code.startsWith("\"\"\"", i)) {
                int close = code.indexOf("\"\"\"", i + 3);
                i = close < 0 ? code.length() : close + 2;
            } else if (c == '"' || c == '\'') {
                for (i++; i < code.length() && code.charAt(i) != c && code.charAt(i) != '\n'; i++) {
                    if (code.charAt(i) == '\\' && i + 1 < code.length() && code.charAt(i + 1) != '\n') {
                        i++;
                    }
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i + 1;
            }
        }
        return code.length();
    }

    /** Reads {@code path<TAB>count<TAB>reason} lines; the reason must not be empty. */
    private static Map<String, Integer> readExceptions() throws IOException {
        Map<String, Integer> exceptions = new TreeMap<>();
        Path file = Path.of("src/test/resources/arch/han-literal-exceptions.txt");
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t");
            assertTrue(parts.length == 3 && !parts[2].isBlank(), () -> "Write exceptions as path<TAB>count<TAB>reason: " + line);
            exceptions.put(parts[0], Integer.parseInt(parts[1].trim()));
        }
        return exceptions;
    }

    /** Strips Java and Kotlin line and block comments, keeping string and text block contents; the line count is unchanged. */
    static List<String> codeLines(String source) {
        return lex(source).code();
    }

    /** Code and comment text of each source line; string and text block contents count as code. */
    record Lexed(List<String> code, List<String> comments) {
    }

    static Lexed lex(String source) {
        List<String> lines = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        StringBuilder comment = new StringBuilder();
        boolean block = false;
        boolean lineComment = false;
        boolean string = false;
        boolean textBlock = false;
        boolean character = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (c == '\n') {
                lines.add(line.toString());
                comments.add(comment.toString());
                line.setLength(0);
                comment.setLength(0);
                lineComment = false;
                string = false;
                character = false;
                continue;
            }
            if (lineComment) {
                comment.append(c);
                continue;
            }
            if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    i++;
                } else {
                    comment.append(c);
                }
                continue;
            }
            if (textBlock) {
                line.append(c);
                if (source.startsWith("\"\"\"", i)) {
                    line.append("\"\"");
                    i += 2;
                    textBlock = false;
                }
                continue;
            }
            if (string || character) {
                line.append(c);
                if (c == '\\') {
                    if (i + 1 < source.length() && source.charAt(i + 1) != '\n') {
                        line.append(source.charAt(++i));
                    }
                } else if (string && c == '"' || character && c == '\'') {
                    string = false;
                    character = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                block = true;
                i++;
                continue;
            }
            if (source.startsWith("\"\"\"", i)) {
                textBlock = true;
                line.append("\"\"\"");
                i += 2;
                continue;
            }
            if (c == '"') {
                string = true;
            } else if (c == '\'') {
                character = true;
            }
            line.append(c);
        }
        lines.add(line.toString());
        comments.add(comment.toString());
        return new Lexed(lines, comments);
    }
}
