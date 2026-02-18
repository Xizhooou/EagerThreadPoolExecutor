package com.xizhooou.eagerthreadpool.alert;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RejectAlertState {
    private final AtomicBoolean enabled;
    @Getter
    private final RollingWindowCounter rollingCounter;
    @Getter
    private final AtomicLong lastAlertAtMs = new AtomicLong(0);

    public RejectAlertState(boolean enabled, RollingWindowCounter rollingCounter) {
        this.enabled = new AtomicBoolean(enabled);
        this.rollingCounter = rollingCounter;
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
    }

}
