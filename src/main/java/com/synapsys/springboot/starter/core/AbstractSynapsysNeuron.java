package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.NeuronState;
import com.synapsys.springboot.starter.model.NeuronType;

public abstract class AbstractSynapsysNeuron implements SynapsysNeuronContract, StatefulNeuron {

    private String name;
    private NeuronType type;
    private NeuronState currentState;
    private NeuronState desiredState;

    @Override
    public final String getName() {
        return name;
    }

    protected void setName(String name) {
        this.name = name;
    }

    public NeuronType getType() {
        return type;
    }

    public void setType(NeuronType type) {
        this.type = type;
    }

    @Override
    public NeuronState getDesiredState() {
        return desiredState;
    }

    protected void setDesiredState(NeuronState desiredState) {
        this.desiredState = desiredState;
    }

    @Override
    public NeuronState getCurrentState() {
        return currentState;
    }

    protected void setCurrentState(NeuronState currentState) {
        this.currentState = currentState;
    }

    @Override
    public final void start() {
        try {
            this.currentState = NeuronState.RUNNING;
            run();
            this.currentState = NeuronState.IDLE;
        } catch (Exception e) {
            this.currentState = NeuronState.IDLE;
            throw new RuntimeException("Failed to start neuron: " + getName(), e);
        }
    }

    protected final void stop() {
        if (currentState != NeuronState.RUNNING) {
            throw new IllegalStateException("Cannot stop neuron '" + getName() + "' from state " + currentState);
        }
        try {
            setCurrentState(NeuronState.STOPPING);
            onStop();
            setCurrentState(NeuronState.IDLE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to stop neuron: " + getName(), e);
        }
    }

    protected abstract void run() throws Exception;

    protected void onStop() throws Exception {};

}
