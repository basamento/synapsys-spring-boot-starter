package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.dto.request.ProcessLogDTO;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProcessLogCaptureTest {

    @Test
    public void capturesStdoutAndStderrForAnExecution() {
        ProcessLogCapture capture = new ProcessLogCapture();
        capture.install();

        List<ProcessLogDTO> logs;
        try {
            try (ProcessLogCapture.Scope ignored = capture.open("demo", 42L)) {
                System.out.println("hello stdout");
                System.err.println("hello stderr");
            }
            logs = capture.drain(10, 10_000);
        } finally {
            // Replacing System.out is global: leaving it installed leaks into other tests.
            capture.uninstall();
        }

        assertEquals(2, logs.size());
        assertEquals("demo", logs.get(0).getProcessName());
        assertEquals(Long.valueOf(42L), logs.get(0).getRunId());
        assertEquals("hello stdout", logs.get(0).getMessage());
        assertEquals("stdout", logs.get(0).getSource());
        assertEquals("stderr", logs.get(1).getSource());
        assertTrue(logs.get(1).getSequence() > logs.get(0).getSequence());
    }

    /**
     * Core validates the logs array atomically and answers 400 for the whole heartbeat
     * when one entry has a blank trimmed message. The client requeues the rejected
     * batch at the front, so a single blank line printed by a process would be resent
     * forever and no command would ever be applied again.
     */
    @Test
    public void doesNotEnqueueBlankLines() {
        ProcessLogCapture capture = new ProcessLogCapture();
        capture.install();

        List<ProcessLogDTO> logs;
        try {
            try (ProcessLogCapture.Scope ignored = capture.open("demo", 42L)) {
                System.out.println("");
                System.out.println("   ");
                System.out.println("\t \t");
                // Non-breaking space: blank to Go's strings.TrimSpace, which is what
                // Core validates with, but not to Java's String.isBlank().
                System.out.println("\u00A0\u202F");
                System.err.println("   ");
                System.out.println("kept");
            }
            logs = capture.drain(10, 10_000);
        } finally {
            capture.uninstall();
        }

        assertEquals(1, logs.size());
        assertEquals("kept", logs.get(0).getMessage());
        // A dropped line must not burn a sequence number: Core requires 1 or greater
        // and orders a run's output by it.
        assertEquals(Long.valueOf(1L), logs.get(0).getSequence());
    }
}
