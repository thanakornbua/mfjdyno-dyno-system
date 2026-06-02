package com.dyno.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class RepeatabilityMetricDto {
    private double min;
    private double max;
    private double mean;
    @JsonProperty("span_percent") private double spanPercent;
    @JsonProperty("per_run") private List<Double> perRun;

    public double getMin() { return min; }
    public double getMax() { return max; }
    public double getMean() { return mean; }
    public double getSpanPercent() { return spanPercent; }
    public List<Double> getPerRun() { return perRun; }
}
