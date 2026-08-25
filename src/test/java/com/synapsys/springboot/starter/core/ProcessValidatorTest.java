package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.config.SynapsysProcessProperties;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;

import static org.junit.Assert.assertThrows;

public class ProcessValidatorTest {

    @Test
    public void rejectsDuplicateProcessNamesWithinWorker() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("first", FirstProcess.class);
            context.registerBean("second", SecondProcess.class);
            context.refresh();

            assertThrows(
                    IllegalStateException.class,
                    () -> ProcessValidator.validateAndGetBeans(context)
            );
        }
    }

    @Test
    public void rejectsSubSecondHeartbeatIntervals() {
        SynapsysProcessProperties properties = new SynapsysProcessProperties();
        properties.setCoreUrl("http://localhost:8080");
        properties.setWorkerName("test-worker");
        properties.setHeartbeatInterval(Duration.ofMillis(500));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            SynapsysProcessProcessor processor = new SynapsysProcessProcessor(context, properties);
            assertThrows(IllegalStateException.class, processor::afterPropertiesSet);
        }
    }

    @SynapsysProcess(name = "duplicate")
    static class FirstProcess extends AbstractEndlessProcess {
        @Override
        protected void runForever(StopSignal stopSignal) { }
    }

    @SynapsysProcess(name = "duplicate")
    static class SecondProcess extends AbstractEndlessProcess {
        @Override
        protected void runForever(StopSignal stopSignal) { }
    }
}
