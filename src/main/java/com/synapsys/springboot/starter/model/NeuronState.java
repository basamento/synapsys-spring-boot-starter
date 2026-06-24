package com.synapsys.springboot.starter.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum NeuronState {

    IDLE("idle"),
    RUNNING("running"),
    STOPPING("stopping"),
    FAILED("failed");

    private final String value;

    NeuronState(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static NeuronState fromValue(String value) {
        for (NeuronState state : values()) {
            if (state.value.equalsIgnoreCase(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown NeuronState: " + value);
    }

    @Override
    public String toString() {
        return this.value;
    }

}
