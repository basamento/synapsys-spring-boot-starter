package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;
import com.synapsys.springboot.starter.model.ProcessType;

import java.util.concurrent.ExecutorService;

public abstract class AbstractSynapsysProcess implements SynapsysProcessContract, StatefulProcess {

    public enum Command { NONE, START, STOP }

    /**
     * What a command handler did with the command it was given.
     *
     * <p>{@link #DEFERRED} means the command was understood but cannot be applied yet: a
     * start arriving while the previous execution is still stopping. The caller must
     * leave the token unacknowledged so Core redelivers it on a later heartbeat.
     * Acknowledging a deferred start as a no-op loses that run forever.
     */
    public enum Outcome { HANDLED, DEFERRED }

    public record CommandDecision(Command command, long token, Long runId) {}

    private String name;
    private ProcessType type;

    private volatile ProcessState currentState = ProcessState.IDLE;
    private volatile ProcessState desiredState = ProcessState.IDLE;

    private volatile long desiredToken = 0;
    private volatile long ackToken = 0;

    // The run id Core has assigned to the active execution. Reported on every beat and
    // attached to every captured log line, so it must only ever describe the execution
    // that is actually running.
    private volatile Long desiredRunId = null;
    private volatile ProcessLogCapture logCapture;

    @Override
    public final String getName() { return name; }

    protected final void setName(String name) { this.name = name; }

    public final ProcessType getType() { return type; }

    public final void setType(ProcessType type) { this.type = type; }

    @Override
    public final ProcessState getDesiredState() { return desiredState; }

    @Override
    public final ProcessState getCurrentState() { return currentState; }

    public final Long getDesiredToken() { return desiredToken; }

    public final Long getAckToken() { return ackToken; }

    public final Long getDesiredRunId() { return desiredRunId; }

    final void setLogCapture(ProcessLogCapture logCapture) { this.logCapture = logCapture; }

    protected final ProcessLogCapture.Scope openExecutionLogScope(Long runId) {
        ProcessLogCapture capture = logCapture;
        return capture == null ? () -> {} : capture.open(getName(), runId);
    }

    protected final void captureExecutionException(Throwable throwable) {
        ProcessLogCapture capture = logCapture;
        if (capture != null) capture.captureException(throwable);
    }

    protected final void setCurrentState(ProcessState currentState) { this.currentState = currentState; }

    protected final void setDesiredStateInternal(ProcessState desiredState) { this.desiredState = desiredState; }

    protected final void setDesiredTokenInternal(long desiredToken) { this.desiredToken = desiredToken; }

    protected final void setAckTokenInternal(long ackToken) { this.ackToken = ackToken; }

    protected final void setDesiredRunIdInternal(Long desiredRunId) { this.desiredRunId = desiredRunId; }

    /**
     * Core command ingestion: token-gated.
     * <ul>
     *   <li>IDLE means "no new command", never a stop.</li>
     *   <li>STOP is only STOPPING.</li>
     *   <li>The caller acknowledges only after its local command handler reports HANDLED.</li>
     * </ul>
     */
    public final synchronized CommandDecision acceptCoreCommand(
            ProcessState coreDesiredState,
            Long coreDesiredToken,
            Long coreRunId
    ) {
        // A command with no token cannot be de-duplicated, so it is not a command.
        if (coreDesiredToken == null) {
            return new CommandDecision(Command.NONE, ackToken, null);
        }
        long token = coreDesiredToken;

        // Duplicate or stale command. The run id still describes the active execution,
        // so it is tracked even once the token has been acknowledged.
        if (token <= ackToken) {
            setDesiredRunIdInternal(coreRunId);
            return new CommandDecision(Command.NONE, ackToken, null);
        }

        setDesiredStateInternal(coreDesiredState);
        setDesiredTokenInternal(token);

        if (coreDesiredState == ProcessState.RUNNING) {
            // The new run id is deliberately NOT adopted here. A start that arrives while
            // the previous run is still stopping is deferred, and replacing the active
            // run's id would attach its remaining output to an execution that has not
            // begun. handleStart adopts it when it accepts the command.
            return new CommandDecision(Command.START, token, coreRunId);
        }

        setDesiredRunIdInternal(coreRunId);

        if (coreDesiredState == ProcessState.STOPPING) {
            return new CommandDecision(Command.STOP, token, null);
        }

        // IDLE, and any desired state a future Core invents, are acknowledged and acted
        // on in no way at all.
        return new CommandDecision(Command.NONE, token, null);
    }

    /** Records a command only after the local handler has accepted it. */
    public final synchronized void acknowledgeCoreCommand(long token) {
        if (token > ackToken) {
            setAckTokenInternal(token);
        }
    }

    /**
     * Execute START according to type implementation.
     * ProcessHeartbeatManager supplies the progressiveExecutor for PROGRESSIVE processes.
     *
     * @return {@link Outcome#HANDLED} when the process is running, or already was;
     *         {@link Outcome#DEFERRED} when the previous execution has not finished.
     */
    public abstract Outcome handleStart(Long runId, ExecutorService progressiveExecutor);

    /**
     * Execute STOP according to type implementation. A stop for a process that is not
     * running is an idempotent handled no-op.
     */
    public abstract Outcome handleStop();

    /**
     * Whether a body, or a cleanup hook belonging to it, has not yet returned.
     *
     * <p>{@code currentState} alone is not enough: business logic that ignores
     * cancellation stays alive after the state has gone back to idle, and a replacement
     * execution must not overlap it.
     */
    public abstract boolean isInFlight();

    /**
     * Waits for any in-flight execution to finish.
     *
     * @return true when nothing is left running, false when the wait timed out.
     */
    public abstract boolean settle(long timeoutMillis);
}
