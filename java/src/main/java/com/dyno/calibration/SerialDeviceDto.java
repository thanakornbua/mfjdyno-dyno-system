package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class SerialDeviceDto {
    private String path;
    private String label;

    @JsonProperty("is_esp32_guess")
    private Boolean esp32Guess;

    public String getPath() {
        return path;
    }

    public String getLabel() {
        return label;
    }

    public boolean isEsp32Guess() {
        return esp32Guess != null && esp32Guess.booleanValue();
    }
}
