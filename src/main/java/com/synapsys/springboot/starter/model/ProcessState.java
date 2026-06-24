package com.synapsys.springboot.starter.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProcessState {

    IDLE("idle"),
    RUNNING("running"),
    STOPPING("stopping"),
    FAILED("failed");

    private final String value;

    ProcessState(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ProcessState fromValue(String value) {
        for (ProcessState state : values()) {
            if (state.value.equalsIgnoreCase(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown ProcessState: " + value);
    }

    @Override
    public String toString() {
        return this.value;
    }

}
