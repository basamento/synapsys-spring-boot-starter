package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;
import com.synapsys.springboot.starter.model.ProcessType;

import java.util.concurrent.ExecutorService;

public abstract class AbstractSynapsysProcess implements SynapsysProcessContract, StatefulProcess {

    public enum Command { NONE, START, STOP }

    public record CommandDecision(Command command, long token, Long runId) {}

    private String name;
    private ProcessType type;

    private volatile ProcessState currentState = ProcessState.IDLE;
    private volatile ProcessState desiredState = ProcessState.IDLE;

    private volatile long desiredToken = 0;
    private volatile long ackToken = 0;

    // Only meaningful for PROGRESSIVE, but we keep it here as "last desired runId"
    // so heartbeat can report it (optional).
    private volatile Long desiredRunId = null;

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

    protected final void setCurrentState(ProcessState currentState) { this.currentState = currentState; }

    protected final void setDesiredStateInternal(ProcessState desiredState) { this.desiredState = desiredState; }

    protected final void setDesiredTokenInternal(long desiredToken) { this.desiredToken = desiredToken; }

    protected final void setAckTokenInternal(long ackToken) { this.ackToken = ackToken; }

    protected final void setDesiredRunIdInternal(Long desiredRunId) { this.desiredRunId = desiredRunId; }

    /**
     * Core command ingestion: token-gated.
     * - IDLE means "no new command" => no STOP
     * - STOP is only STOPPING
     * - ACK means the command was accepted, not that execution completed.
     */
    public final synchronized CommandDecision acceptCoreCommand(
            ProcessState coreDesiredState,
            Long coreDesiredToken,
            Long coreRunId
    ) {
        long token = coreDesiredToken == null ? 0L : coreDesiredToken;

        // If core doesn't send tokens yet, you can choose to treat it as "always new"
        // but that defeats idempotency. Better: require token.
        if (token == 0L && coreDesiredToken == null) {
            // safest behavior: do nothing
            return new CommandDecision(Command.NONE, ackToken, null);
        }

        // Duplicate/old command
        if (token <= ackToken) {
            return new CommandDecision(Command.NONE, ackToken, null);
        }

        setDesiredStateInternal(coreDesiredState);
        setDesiredTokenInternal(token);
        setAckTokenInternal(token);
        setDesiredRunIdInternal(coreRunId);

        // IDLE is a no-op, never a stop
        if (coreDesiredState == ProcessState.IDLE) {
            return new CommandDecision(Command.NONE, token, coreRunId);
        }

        if (coreDesiredState == ProcessState.RUNNING) {
            return new CommandDecision(Command.START, token, coreRunId);
        }

        if (coreDesiredState == ProcessState.STOPPING) {
            return new CommandDecision(Command.STOP, token, null);
        }

        return new CommandDecision(Command.NONE, token, null);
    }

    /**
     * Execute START according to type implementation.
     * ProcessHeartbeatManager supplies the progressiveExecutor for PROGRESSIVE processes.
     */
    public abstract void handleStart(Long runId, ExecutorService progressiveExecutor);

    /**
     * Execute STOP according to type implementation.
     */
    public abstract void handleStop();
}
