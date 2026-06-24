package com.synapsys.springboot.starter.model.dto.request;

import com.synapsys.springboot.starter.model.ProcessState;
import com.synapsys.springboot.starter.model.ProcessType;

public class ProcessInfoDTO {

    private String name;
    private ProcessType type;
    private ProcessState currentState;
    private ProcessState desiredState;
    private Long desiredToken;
    private Long runId;
    private Long ackToken;

    public ProcessInfoDTO() {}

    public ProcessInfoDTO(String name, ProcessType type, ProcessState currentState, ProcessState desiredState) {
        this.name = name;
        this.type = type;
        this.currentState = currentState;
        this.desiredState = desiredState;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ProcessType getType() {
        return type;
    }

    public void setType(ProcessType type) {
        this.type = type;
    }

    public ProcessState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(ProcessState currentState) {
        this.currentState = currentState;
    }

    public ProcessState getDesiredState() {
        return desiredState;
    }

    public void setDesiredState(ProcessState desiredState) {
        this.desiredState = desiredState;
    }

    public Long getDesiredToken() {
        return desiredToken;
    }

    public void setDesiredToken(Long desiredToken) {
        this.desiredToken = desiredToken;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Long getAckToken() {
        return ackToken;
    }

    public void setAckToken(Long ackToken) {
        this.ackToken = ackToken;
    }

}
