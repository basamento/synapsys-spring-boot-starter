package com.synapsys.springboot.starter.model.dto.request;

public class RegisterNeuronRequestDTO {

    private String name;
    private Long pingTiming;

    public RegisterNeuronRequestDTO(String name, Long pingTiming) {
        this.name = name;
        this.pingTiming = pingTiming;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getPingTiming() {
        return pingTiming;
    }

    public void setPingTiming(Long pingTiming) {
        this.pingTiming = pingTiming;
    }

}
