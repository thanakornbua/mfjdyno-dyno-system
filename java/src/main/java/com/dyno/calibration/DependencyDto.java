package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class DependencyDto {
    private String name;
    private String category;
    private Boolean required;
    private String status;
    private String detail;
    private String remediation;

    @JsonProperty("blocks_flashing")
    private Boolean blocksFlashing;

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public boolean isRequired() {
        return required != null && required.booleanValue();
    }

    public String getStatus() {
        return status;
    }

    public boolean isOk() {
        return "ok".equals(status);
    }

    public boolean isMissing() {
        return "missing".equals(status);
    }

    public boolean isUnknown() {
        return "unknown".equals(status);
    }

    public String getDetail() {
        return detail;
    }

    public String getRemediation() {
        return remediation;
    }

    /** True when this dependency being missing makes an ESP flash attempt pointless. */
    public boolean blocksFlashing() {
        return blocksFlashing != null && blocksFlashing.booleanValue();
    }
}
