package com.synapsys.springboot.starter.model.dto.request;

import com.synapsys.springboot.starter.model.NeuronState;

public class NeuronInfoDTO {

    private String name;
    private NeuronState currentState;
    private NeuronState desiredState;

    public NeuronInfoDTO(String name, NeuronState currentState, NeuronState desiredState) {
        this.name = name;
        this.currentState = currentState;
        this.desiredState = desiredState;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

}
