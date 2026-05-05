package com.dyno.control;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class RunControlResponse {
    private boolean success;
    private String message;
    private boolean configured;
    private boolean started;

    @JsonProperty("run_label")
    private String runLabel;

    @JsonProperty("license_plate")
    private String licensePlate;

    private int statusCode;

    public RunControlResponse() {
    }

    public static RunControlResponse failure(int statusCode, String message) {
        RunControlResponse response = new RunControlResponse();
        response.success = false;
        response.message = message;
        response.configured = false;
        response.started = false;
        response.statusCode = statusCode;
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public boolean isConfigured() {
        return configured;
    }

    public boolean isStarted() {
        return started;
    }

    public String getRunLabel() {
        return runLabel;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public void normalizeFallbacks(int httpStatus) {
        this.statusCode = httpStatus;
        if (message == null || message.trim().isEmpty()) {
            message = "Control API returned HTTP " + httpStatus;
        }
    }
}
