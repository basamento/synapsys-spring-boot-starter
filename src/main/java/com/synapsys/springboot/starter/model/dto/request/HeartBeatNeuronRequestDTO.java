package com.synapsys.springboot.starter.model.dto.request;

import com.synapsys.springboot.starter.model.NeuronState;

public class HeartBeatNeuronRequestDTO {

    private String name;
    private NeuronState state;
    private Long pingTiming;

    public HeartBeatNeuronRequestDTO(String name, NeuronState state, Long pingTiming) {
        this.name = name;
        this.state = state;
        this.pingTiming = pingTiming;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public NeuronState getState() {
        return state;
    }

    public void setState(NeuronState state) {
        this.state = state;
    }

    public Long getPingTiming() {
        return pingTiming;
    }

    public void setPingTiming(Long pingTiming) {
        this.pingTiming = pingTiming;
    }

}
