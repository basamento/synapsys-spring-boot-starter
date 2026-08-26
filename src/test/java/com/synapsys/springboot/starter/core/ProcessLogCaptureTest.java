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

        try (ProcessLogCapture.Scope ignored = capture.open("demo", 42L)) {
            System.out.println("hello stdout");
            System.err.println("hello stderr");
        }

        List<ProcessLogDTO> logs = capture.drain(10, 10_000);
        assertEquals(2, logs.size());
        assertEquals("demo", logs.get(0).getProcessName());
        assertEquals(Long.valueOf(42L), logs.get(0).getRunId());
        assertEquals("hello stdout", logs.get(0).getMessage());
        assertEquals("stdout", logs.get(0).getSource());
        assertEquals("stderr", logs.get(1).getSource());
        assertTrue(logs.get(1).getSequence() > logs.get(0).getSequence());
    }
}
