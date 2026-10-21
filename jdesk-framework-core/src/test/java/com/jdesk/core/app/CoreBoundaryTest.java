package com.jdesk.core.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreBoundaryTest {

    /** Exempt from the Swing/AWT ban because EDT dispatch must touch Swing; the frame-layer ban exempts no package. */
    private static final Path EDT_PACKAGE = Path.of("com", "jdesk", "concurrency");

    @Test
    void coreMainSourcesDoNotReferenceSwingOrFrameLayer() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> swingForbidden = List.of(
                "javax.swing",
                "java.awt",
                "SwingUtilities",
                "UIManager");
        List<String> frameForbidden = List.of("com.jdesk.frame");

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<String> violations = paths
                    .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".kt"))
                    .flatMap(path -> {
                        boolean edtExempt = sourceRoot.relativize(path).startsWith(EDT_PACKAGE);
                        List<String> forbidden = edtExempt
                                ? frameForbidden
                                : Stream.concat(swingForbidden.stream(), frameForbidden.stream()).toList();
                        return violationsIn(path, forbidden).stream();
                    })
                    .toList();

            assertTrue(violations.isEmpty(), () -> "Core boundary violations:\n" + String.join("\n", violations));
        }
    }

    @Test
    void coreRuntimeParamDeclarationsStayInRuntimeParamsPackage() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> forbidden = List.of("RuntimeParamDescriptor.builder(");

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<String> violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isAllowedRuntimeParamDeclarationFile(sourceRoot, path))
                    .flatMap(path -> violationsIn(path, forbidden).stream())
                    .toList();

            assertTrue(violations.isEmpty(), () -> "Core runtime parameter declaration violations:\n"
                    + String.join("\n", violations));
        }
    }

    private static boolean isAllowedRuntimeParamDeclarationFile(Path sourceRoot, Path path) {
        Path relative = sourceRoot.relativize(path);
        return relative.startsWith(Path.of("com", "jdesk", "core", "runtime", "params"))
                || relative.equals(Path.of("com", "jdesk", "core", "runtime", "RuntimeParamRegistry.java"));
    }

    private static List<String> violationsIn(Path path, List<String> forbidden) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return forbidden.stream()
                    .filter(text::contains)
                    .map(token -> path + " contains " + token)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + path, e);
        }
    }
}
