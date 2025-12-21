package com.synapsys.springboot.starter.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum NeuronType {

    PROGRESSIVE("progressive"),
    ENDLESS("endless");

    private final String value;

    NeuronType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static NeuronType fromValue(String value) {
        for (NeuronType state : values()) {
            if (state.value.equalsIgnoreCase(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown NeuronType: " + value);
    }

    @Override
    public String toString() {
        return this.value;
    }

}