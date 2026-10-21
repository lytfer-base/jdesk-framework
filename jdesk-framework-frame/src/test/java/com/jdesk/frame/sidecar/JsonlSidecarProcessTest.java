package com.jdesk.frame.sidecar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests frame routing, exit detection and cleanup against a {@link FakeJsonlSidecar} child process. */
class JsonlSidecarProcessTest {

    private JsonlSidecarProcess process;

    @AfterEach
    void tearDown() {
        if (process != null) {
            process.dispose();
        }
    }

    /** Doesn't use a temp working directory: on Windows the child's CWD locks it and JUnit cleanup fails. */
    private JsonlSidecarProcess launch() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", exe).toString();
        process = new JsonlSidecarProcess("fake-sidecar",
                List.of(java, "-cp", System.getProperty("java.class.path"), FakeJsonlSidecar.class.getName()),
                Path.of(System.getProperty("user.dir")), Map.of());
        return process;
    }

    @Test
    void deliversStdoutFramesAndStderrLinesSeparately() throws Exception {
        LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<String> stderr = new LinkedBlockingQueue<>();
        JsonlSidecarProcess p = launch();
        p.addFrameListener(frames::add);
        p.addStderrListener(stderr::add);
        p.start();

        assertEquals("{\"hello\":\"fake\"}", frames.poll(10, TimeUnit.SECONDS));
        p.writeLine("你好");
        assertEquals("{\"echo\":\"你好\"}", frames.poll(10, TimeUnit.SECONDS), "stdout frames must be UTF-8");
        p.writeLine("stderr");
        assertEquals("warned", stderr.poll(10, TimeUnit.SECONDS), "logs go to stderr, not into frames");
        assertTrue(frames.isEmpty(), "stderr lines must never reach the frame listeners");
    }

    @Test
    void exitListenerReceivesExitCode() throws Exception {
        CompletableFuture<Integer> exited = new CompletableFuture<>();
        JsonlSidecarProcess p = launch();
        p.addExitListener(exited::complete);
        p.start();
        p.writeLine("exit");
        assertEquals(7, exited.get(10, TimeUnit.SECONDS));
        assertEquals(Optional.of(7), p.exitCode());
    }

    /** A throwing frame listener doesn't stop later listeners or the reader thread; later frames still arrive. */
    @Test
    void aThrowingFrameListenerDoesNotStopTheReader() throws Exception {
        LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        JsonlSidecarProcess p = launch();
        p.addFrameListener(line -> {
            throw new IllegalStateException("listener bug");
        });
        p.addFrameListener(frames::add);
        p.start();

        assertEquals("{\"hello\":\"fake\"}", frames.poll(10, TimeUnit.SECONDS));
        p.writeLine("again");
        assertEquals("{\"echo\":\"again\"}", frames.poll(10, TimeUnit.SECONDS));
    }

    /** A throwing exit listener doesn't keep the exit code from the listeners after it. */
    @Test
    void aThrowingExitListenerDoesNotSkipTheOthers() throws Exception {
        CompletableFuture<Integer> exited = new CompletableFuture<>();
        JsonlSidecarProcess p = launch();
        p.addExitListener(code -> {
            throw new IllegalStateException("listener bug");
        });
        p.addExitListener(exited::complete);
        p.start();
        p.writeLine("exit");
        assertEquals(7, exited.get(10, TimeUnit.SECONDS));
    }

    @Test
    void disposeKillsProcessAndRejectsWrites() throws Exception {
        JsonlSidecarProcess p = launch();
        p.start();
        assertTrue(p.isAlive());
        p.dispose();
        assertFalse(p.isAlive(), "the child process must be gone after dispose");
        assertThrows(IllegalStateException.class, () -> p.writeLine("x"));
    }
}
