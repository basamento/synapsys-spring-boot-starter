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

    /**
     * Returns null rather than throwing for a value this client has never heard of.
     *
     * <p>Core is versioned independently of the starter. A state added to Core's enum
     * must not fail deserialization of the whole heartbeat response, because that would
     * cost every process on that beat its command — in the field, at a moment the user
     * cannot fix, for a reason they cannot see. An unrecognised desired state is
     * acknowledged and acted on in no way at all.
     */
    @JsonCreator
    public static ProcessState fromValue(String value) {
        for (ProcessState state : values()) {
            if (state.value.equalsIgnoreCase(value)) {
                return state;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return this.value;
    }

}
