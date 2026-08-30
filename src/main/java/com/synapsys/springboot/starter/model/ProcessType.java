package com.synapsys.springboot.starter.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProcessType {

    PROGRESSIVE("progressive"),
    ENDLESS("endless");

    private final String value;

    ProcessType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** Returns null for an unrecognised value, for the reason given on ProcessState. */
    @JsonCreator
    public static ProcessType fromValue(String value) {
        for (ProcessType state : values()) {
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