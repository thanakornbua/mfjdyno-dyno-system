package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class FlashStatusDto {
    private String state;
    private String log;
    private String port;

    @JsonProperty("started_at_ms")
    private Long startedAtMs;

    @JsonProperty("finished_at_ms")
    private Long finishedAtMs;

    /** One of: idle, running, success, error. */
    public String getState() {
        return state == null ? "idle" : state;
    }

    public String getLog() {
        return log == null ? "" : log;
    }

    public String getPort() {
        return port;
    }

    public boolean isRunning() {
        return "running".equals(state);
    }

    public boolean isSuccess() {
        return "success".equals(state);
    }

    public boolean isError() {
        return "error".equals(state);
    }

    public boolean isTerminal() {
        return isSuccess() || isError();
    }
}
