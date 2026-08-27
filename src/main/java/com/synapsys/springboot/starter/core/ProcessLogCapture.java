package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.dto.request.ProcessLogDTO;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Captures process-owned console lines while leaving the application's console intact. */
public final class ProcessLogCapture {

    private static final int MAX_QUEUE_SIZE = 10_000;
    private static final int MAX_LINE_LENGTH = 16_000;
    private static volatile ProcessLogCapture active;
    private static PrintStream originalOut;
    private static PrintStream originalErr;
    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();

    private final Object queueLock = new Object();
    private final Deque<ProcessLogDTO> queue = new ArrayDeque<>();

    public void install() {
        synchronized (ProcessLogCapture.class) {
            if (active == null) {
                originalOut = System.out;
                originalErr = System.err;
                System.setOut(new TeePrintStream(System.out, "stdout"));
                System.setErr(new TeePrintStream(System.err, "stderr"));
            }
            active = this;
        }
    }

    /**
     * Restores the console streams this capture replaced. Replacing System.out is global
     * and process-wide, so it must be undone when the owning context shuts down, or a
     * later context inherits streams that feed a dead queue.
     */
    public void uninstall() {
        synchronized (ProcessLogCapture.class) {
            if (active != this) return;
            active = null;
            // Only restore streams still owned by this class: something installed later
            // must not be clobbered.
            if (System.out instanceof TeePrintStream && originalOut != null) {
                System.setOut(originalOut);
            }
            if (System.err instanceof TeePrintStream && originalErr != null) {
                System.setErr(originalErr);
            }
            originalOut = null;
            originalErr = null;
        }
    }

    public Scope open(String processName, Long runId) {
        ExecutionContext previous = CURRENT.get();
        ExecutionContext context = new ExecutionContext(this, processName, runId);
        CURRENT.set(context);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public List<ProcessLogDTO> drain(int maxEntries, int maxBytes) {
        List<ProcessLogDTO> result = new ArrayList<>();
        int bytes = 0;
        synchronized (queueLock) {
            while (!queue.isEmpty() && result.size() < maxEntries) {
                ProcessLogDTO next = queue.peekFirst();
                int nextBytes = next.getMessage() == null
                        ? 0
                        : next.getMessage().getBytes(StandardCharsets.UTF_8).length;
                if (!result.isEmpty() && bytes + nextBytes > maxBytes) break;
                result.add(queue.removeFirst());
                bytes += nextBytes;
            }
        }
        return result;
    }

    public void requeueFirst(List<ProcessLogDTO> entries) {
        synchronized (queueLock) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (queue.size() >= MAX_QUEUE_SIZE) queue.removeLast();
                queue.addFirst(entries.get(i));
            }
        }
    }

    private void capture(String source, String line) {
        if (line == null || line.isEmpty()) return;
        ExecutionContext context = CURRENT.get();
        if (context == null || context.capture != this || context.runId == null) return;

        String message = line.length() > MAX_LINE_LENGTH
                ? line.substring(0, MAX_LINE_LENGTH) + "…"
                : line;
        ProcessLogDTO entry = new ProcessLogDTO(
                context.processName,
                context.runId,
                context.sequence.incrementAndGet(),
                Instant.now().toString(),
                source,
                "stderr".equals(source) ? "error" : "info",
                message
        );
        synchronized (queueLock) {
            if (queue.size() >= MAX_QUEUE_SIZE) queue.removeFirst();
            queue.addLast(entry);
        }
    }

    public void captureException(Throwable throwable) {
        if (throwable == null) return;
        StringWriter output = new StringWriter();
        throwable.printStackTrace(new PrintWriter(output));
        for (String line : output.toString().split("\\R")) capture("stderr", line);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private record ExecutionContext(ProcessLogCapture capture, String processName, Long runId,
                                    AtomicLong sequence) {
        private ExecutionContext(ProcessLogCapture capture, String processName, Long runId) {
            this(capture, processName, runId, new AtomicLong());
        }
    }

    private final class TeePrintStream extends PrintStream {
        private final PrintStream delegate;
        private final String source;
        private final ThreadLocal<ByteArrayOutputStream> partial =
                ThreadLocal.withInitial(ByteArrayOutputStream::new);

        private TeePrintStream(PrintStream delegate, String source) {
            super(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
            this.delegate = delegate;
            this.source = source;
        }

        @Override
        public void write(int value) {
            delegate.write(value);
            append(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            delegate.write(buffer, offset, length);
            append(buffer, offset, length);
        }

        @Override
        public void flush() {
            ByteArrayOutputStream line = partial.get();
            if (line.size() > 0) {
                ProcessLogCapture current = active;
                if (current != null) current.capture(source, line.toString(StandardCharsets.UTF_8));
                line.reset();
            }
            delegate.flush();
        }

        @Override
        public void close() {
            flush();
        }

        private void append(byte[] buffer, int offset, int length) {
            ByteArrayOutputStream line = partial.get();
            for (int i = offset; i < offset + length; i++) {
                if (buffer[i] == '\n') {
                    ProcessLogCapture current = active;
                    if (current != null) current.capture(source, line.toString(StandardCharsets.UTF_8));
                    line.reset();
                } else if (buffer[i] != '\r') {
                    line.write(buffer[i]);
                }
            }
        }
    }
}
