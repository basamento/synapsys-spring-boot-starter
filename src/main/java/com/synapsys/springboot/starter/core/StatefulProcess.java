package com.synapsys.springboot.starter.core;

import com.synapsys.springboot.starter.model.ProcessState;

public interface StatefulProcess {
    ProcessState getCurrentState();
    ProcessState getDesiredState();
}
