package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.NeuronState;

public abstract class AbstractSynapsysNeuron implements SynapsysNeuronContract, StatefulNeuron {

    private String name;
    private NeuronState currentState;
    private NeuronState desiredState;

    @Override
    public final String getName() {
        return name;
    }

    protected void setName(String name) {
        this.name = name;
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
            run();
            this.currentState = NeuronState.RUNNING;;
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
