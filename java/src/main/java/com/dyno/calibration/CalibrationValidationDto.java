package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class CalibrationValidationDto {
    @JsonProperty("is_valid")
    private Boolean valid;

    private List<String> warnings;
    private List<String> errors;

    public CalibrationValidationDto() {
    }

    private CalibrationValidationDto(Boolean valid, List<String> warnings, List<String> errors) {
        this.valid = valid;
        this.warnings = warnings;
        this.errors = errors;
    }

    public static CalibrationValidationDto of(boolean valid, List<String> warnings, List<String> errors) {
        return new CalibrationValidationDto(
            Boolean.valueOf(valid),
            warnings == null ? null : new ArrayList<String>(warnings),
            errors == null ? null : new ArrayList<String>(errors)
        );
    }

    public Boolean getValid() {
        return valid;
    }

    public boolean isValid() {
        return Boolean.TRUE.equals(valid);
    }

    public List<String> getWarnings() {
        if (warnings == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(warnings);
    }

    public List<String> getErrors() {
        if (errors == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(errors);
    }
}
