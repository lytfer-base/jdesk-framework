package com.jdesk.frame.sidecar;

import com.jdesk.core.disposer.Disposable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Sidecar child process that speaks JSONL, one frame per line: starts it, reads stdout (protocol frames) and stderr
 * (logs) separately, detects its exit and reaps it. Callbacks run on dedicated daemon threads, not the EDT.
 * Don't block on reads or call waitFor on the EDT.
 */
public class JsonlSidecarProcess implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(JsonlSidecarProcess.class);

    private final String name;
    private final List<String> command;
    private final Path workingDir;
    private final Map<String, String> extraEnv;

    private volatile Process process;
    private volatile Writer stdinWriter;
    private final Object writeLock = new Object();
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private volatile Integer exitCode;

    private final List<Consumer<String>> frameListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> stderrListeners = new CopyOnWriteArrayList<>();
    private final List<IntConsumer> exitListeners = new CopyOnWriteArrayList<>();

    /** @param name process name used in thread names and logs, such as {@code acp-agent} */
    public JsonlSidecarProcess(String name, List<String> command, Path workingDir, Map<String, String> extraEnv) {
        this.name = name;
        this.command = List.copyOf(command);
        this.workingDir = workingDir;
        this.extraEnv = Map.copyOf(extraEnv);
    }

    public void addFrameListener(Consumer<String> listener) {
        frameListeners.add(listener);
    }

    public void addStderrListener(Consumer<String> listener) {
        stderrListeners.add(listener);
    }

    public void addExitListener(IntConsumer listener) {
        exitListeners.add(listener);
    }

    public String name() {
        return name;
    }

    public long pid() {
        Process p = process;
        return p == null ? -1 : p.pid();
    }

    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** Returns the exit code, or empty if the process hasn't started or exited. */
    public Optional<Integer> exitCode() {
        return Optional.ofNullable(exitCode);
    }

    /**
     * Returns the stdin writer for raw frames, always UTF-8 rather than the Windows code page; lock it yourself when
     * writing from several threads, or use {@link #writeLine}.
     */
    public Writer stdin() {
        Writer writer = stdinWriter;
        if (writer == null) {
            throw new IllegalStateException(name + " process not started");
        }
        return writer;
    }

    /** Writes one frame line and flushes; throws IllegalStateException if not started or already disposed. */
    public void writeLine(String line) throws IOException {
        Writer writer = stdinWriter;
        if (writer == null || disposed.get()) {
            throw new IllegalStateException(name + " process not available");
        }
        synchronized (writeLock) {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }
    }

    public synchronized void start() {
        if (process != null) {
            throw new IllegalStateException("process already started");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.environment().putAll(extraEnv);
        // Never merge stderr into stdout: log lines would corrupt the protocol frames.
        pb.redirectErrorStream(false);
        try {
            process = pb.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start " + name + " process: " + command, e);
        }
        stdinWriter = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        LOG.info("sidecar.process.started name={} pid={} cmd={}", name, pid(), String.join(" ", command));

        Thread stdoutReader = new Thread(
                () -> readLoop(process.getInputStream(), "stdout", line -> deliver(frameListeners, line, "stdout")),
                name + "-stdout");
        stdoutReader.setDaemon(true);
        stdoutReader.start();
        Thread stderrReader = new Thread(
                () -> readLoop(process.getErrorStream(), "stderr", line -> deliver(stderrListeners, line, "stderr")),
                name + "-stderr");
        stderrReader.setDaemon(true);
        stderrReader.start();

        process.onExit().thenAccept(p -> {
            exitCode = p.exitValue();
            LOG.info("sidecar.process.exited name={} pid={} exitCode={}", name, p.pid(), exitCode);
            for (IntConsumer listener : exitListeners) {
                try {
                    listener.accept(p.exitValue());
                } catch (RuntimeException failure) {
                    LOG.warn("sidecar.process.exitListenerFailed name={}", name, failure);
                }
            }
        });
    }

    /**
     * Hands the line to each listener; one that throws must not stop the others or the reader thread, or the undrained
     * pipe would fill up and block the child.
     */
    private void deliver(List<Consumer<String>> listeners, String line, String tag) {
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(line);
            } catch (RuntimeException failure) {
                LOG.warn("sidecar.process.{}ListenerFailed name={}", tag, name, failure);
            }
        }
    }

    private void readLoop(InputStream stream, String tag, Consumer<String> onLine) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onLine.accept(line);
            }
        } catch (Exception e) {
            if (!disposed.get()) {
                LOG.warn("sidecar.process.{} reader failed name={}", tag, name, e);
            }
        }
        LOG.debug("sidecar.process.{} reader EOF name={}", tag, name);
    }

    /** Reaps the child process: destroy first, destroyForcibly after a timeout, then check that it is gone. */
    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        Process p = process;
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                LOG.warn("sidecar.process did not exit in 5s, destroying forcibly: name={} pid={}", name, p.pid());
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        if (!p.isAlive() && exitCode == null) {
            exitCode = p.exitValue();
        }
        LOG.info("sidecar.process.reaped name={} pid={} alive={} exitCode={}", name, p.pid(), p.isAlive(), exitCode);
    }
}
