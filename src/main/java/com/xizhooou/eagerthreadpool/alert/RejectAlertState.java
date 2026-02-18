package com.xizhooou.eagerthreadpool.alert;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RejectAlertState {
    private final AtomicBoolean enabled;
    private final RollingWindowCounter rollingCounter;
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

    public RollingWindowCounter getRollingCounter() {
        return rollingCounter;
    }

    public AtomicLong getLastAlertAtMs() {
        return lastAlertAtMs;
    }
}
