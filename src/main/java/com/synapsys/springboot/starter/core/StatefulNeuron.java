package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.NeuronState;

public interface StatefulNeuron {
    NeuronState getCurrentState();
    NeuronState getDesiredState();
}
