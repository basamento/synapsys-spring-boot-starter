package com.synapsys.springboot.starter.model.dto.request;

import com.synapsys.springboot.starter.model.NeuronState;
import com.synapsys.springboot.starter.model.NeuronType;

public class NeuronInfoDTO {

    private String name;
    private NeuronType type;
    private NeuronState currentState;
    private NeuronState desiredState;
    private Long desiredToken;
    private Long runId;
    private Long ackToken;

    public NeuronInfoDTO() {}

    public NeuronInfoDTO(String name, NeuronType type, NeuronState currentState, NeuronState desiredState) {
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

    public NeuronType getType() {
        return type;
    }

    public void setType(NeuronType type) {
        this.type = type;
    }

    public NeuronState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(NeuronState currentState) {
        this.currentState = currentState;
    }

    public NeuronState getDesiredState() {
        return desiredState;
    }

    public void setDesiredState(NeuronState desiredState) {
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
