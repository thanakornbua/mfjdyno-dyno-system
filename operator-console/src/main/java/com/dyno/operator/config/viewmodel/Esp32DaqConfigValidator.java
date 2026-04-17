package com.dyno.operator.config.viewmodel;

import com.dyno.operator.config.model.EngineEdgeMode;
import com.dyno.operator.config.model.Esp32DaqConfigUpdateRequestDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Esp32DaqConfigValidator {
    public static final int PIN_MIN = 0;
    public static final int PIN_MAX = 39;
    public static final int CAN_BITRATE_MIN = 10_000;
    public static final int CAN_BITRATE_MAX = 1_000_000;
    public static final int UART_BAUD_MIN = 9_600;
    public static final int UART_BAUD_MAX = 2_000_000;
    public static final int TELEMETRY_RATE_MIN = 1;
    public static final int TELEMETRY_RATE_MAX = 500;

    public ValidationResult validateDraft(DraftInput input) {
        Objects.requireNonNull(input, "input");

        List<ValidationIssue> issues = new ArrayList<>();
        Integer enginePulsePin = parseInt("enginePulsePin", "Engine pulse pin", input.enginePulsePin(), issues);
        Double enginePulsesPerRev = parseDouble(
            "enginePulsesPerRev",
            "Engine pulses per rev",
            input.enginePulsesPerRev(),
            issues
        );
        Integer encoderPin = parseInt("encoderPin", "Encoder pin", input.encoderPin(), issues);
        Integer encoderPpr = parseInt("encoderPpr", "Encoder PPR", input.encoderPpr(), issues);
        Integer canRxPin = parseInt("canRxPin", "CAN RX pin", input.canRxPin(), issues);
        Integer canTxPin = parseInt("canTxPin", "CAN TX pin", input.canTxPin(), issues);
        Integer canBitrate = parseInt("canBitrate", "CAN bitrate", input.canBitrate(), issues);
        Integer uartTxPin = parseInt("uartTxPin", "UART TX pin", input.uartTxPin(), issues);
        Integer uartRxPin = parseInt("uartRxPin", "UART RX pin", input.uartRxPin(), issues);
        Integer uartBaud = parseInt("uartBaud", "UART baud", input.uartBaud(), issues);
        Integer telemetryRateHz = parseInt("telemetryRateHz", "Telemetry rate", input.telemetryRateHz(), issues);

        if (!issues.isEmpty()) {
            return new ValidationResult(List.copyOf(issues), Optional.empty());
        }

        Esp32DaqConfigUpdateRequestDto config = new Esp32DaqConfigUpdateRequestDto(
            enginePulsePin,
            enginePulsesPerRev,
            input.engineEdgeMode() == null ? EngineEdgeMode.RISING : input.engineEdgeMode(),
            encoderPin,
            encoderPpr,
            canRxPin,
            canTxPin,
            canBitrate,
            uartTxPin,
            uartRxPin,
            uartBaud,
            telemetryRateHz
        );
        return validateConfig(config);
    }

    public ValidationResult validateConfig(Esp32DaqConfigUpdateRequestDto config) {
        Objects.requireNonNull(config, "config");

        List<ValidationIssue> issues = new ArrayList<>();

        validatePin("enginePulsePin", "Engine pulse pin", config.enginePulsePin(), issues);
        validatePin("encoderPin", "Encoder pin", config.encoderPin(), issues);
        validatePin("canRxPin", "CAN RX pin", config.canRxPin(), issues);
        validatePin("canTxPin", "CAN TX pin", config.canTxPin(), issues);
        validatePin("uartTxPin", "UART TX pin", config.uartTxPin(), issues);
        validatePin("uartRxPin", "UART RX pin", config.uartRxPin(), issues);

        if (!Double.isFinite(config.enginePulsesPerRev()) || config.enginePulsesPerRev() <= 0.0) {
            issues.add(ValidationIssue.error("enginePulsesPerRev", "Engine pulses per rev must be positive."));
        } else if (config.enginePulsesPerRev() > 8.0) {
            issues.add(ValidationIssue.warning(
                "enginePulsesPerRev",
                "Engine pulses per rev is unusually high for a tach signal."
            ));
        }

        if (config.encoderPpr() <= 0) {
            issues.add(ValidationIssue.error("encoderPpr", "Encoder PPR must be greater than zero."));
        } else if (config.encoderPpr() < 16 || config.encoderPpr() > 4_096) {
            issues.add(ValidationIssue.warning("encoderPpr", "Encoder PPR is outside the common dyno range."));
        }

        if (config.canBitrate() < CAN_BITRATE_MIN || config.canBitrate() > CAN_BITRATE_MAX) {
            issues.add(ValidationIssue.error(
                "canBitrate",
                "CAN bitrate must be within " + CAN_BITRATE_MIN + ".." + CAN_BITRATE_MAX + "."
            ));
        }

        if (config.uartBaud() < UART_BAUD_MIN || config.uartBaud() > UART_BAUD_MAX) {
            issues.add(ValidationIssue.error(
                "uartBaud",
                "UART baud must be within " + UART_BAUD_MIN + ".." + UART_BAUD_MAX + "."
            ));
        } else if (config.uartBaud() > 921_600) {
            issues.add(ValidationIssue.warning(
                "uartBaud",
                "High UART baud rates increase the risk of transport instability on long cables."
            ));
        }

        if (config.telemetryRateHz() < TELEMETRY_RATE_MIN || config.telemetryRateHz() > TELEMETRY_RATE_MAX) {
            issues.add(ValidationIssue.error(
                "telemetryRateHz",
                "Telemetry rate must be within " + TELEMETRY_RATE_MIN + ".." + TELEMETRY_RATE_MAX + " Hz."
            ));
        } else if (config.telemetryRateHz() > 200) {
            issues.add(ValidationIssue.warning(
                "telemetryRateHz",
                "Telemetry rate above 200 Hz may increase UART load without improving operator usability."
            ));
        }

        validatePinConflicts(config, issues);

        return new ValidationResult(List.copyOf(issues), Optional.of(config));
    }

    private static Integer parseInt(String field, String label, String raw, List<ValidationIssue> issues) {
        if (raw == null || raw.isBlank()) {
            issues.add(ValidationIssue.error(field, label + " is required."));
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            issues.add(ValidationIssue.error(field, label + " must be an integer."));
            return null;
        }
    }

    private static Double parseDouble(String field, String label, String raw, List<ValidationIssue> issues) {
        if (raw == null || raw.isBlank()) {
            issues.add(ValidationIssue.error(field, label + " is required."));
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            issues.add(ValidationIssue.error(field, label + " must be numeric."));
            return null;
        }
    }

    private static void validatePin(String field, String label, int value, List<ValidationIssue> issues) {
        if (value < PIN_MIN || value > PIN_MAX) {
            issues.add(ValidationIssue.error(field, label + " must be within " + PIN_MIN + ".." + PIN_MAX + "."));
        }
    }

    private static void validatePinConflicts(Esp32DaqConfigUpdateRequestDto config, List<ValidationIssue> issues) {
        Map<Integer, List<String>> uses = new LinkedHashMap<>();
        registerPin(uses, config.enginePulsePin(), "enginePulsePin");
        registerPin(uses, config.encoderPin(), "encoderPin");
        registerPin(uses, config.canRxPin(), "canRxPin");
        registerPin(uses, config.canTxPin(), "canTxPin");
        registerPin(uses, config.uartTxPin(), "uartTxPin");
        registerPin(uses, config.uartRxPin(), "uartRxPin");

        for (Map.Entry<Integer, List<String>> entry : uses.entrySet()) {
            if (entry.getValue().size() > 1) {
                issues.add(ValidationIssue.error(
                    "pinConflict",
                    "GPIO " + entry.getKey() + " is assigned to multiple functions: " + String.join(", ", entry.getValue()) + "."
                ));
            }
        }

        if (config.canRxPin() == config.canTxPin()) {
            issues.add(ValidationIssue.error("canTxPin", "CAN RX and CAN TX pins must be different."));
        }
        if (config.uartTxPin() == config.uartRxPin()) {
            issues.add(ValidationIssue.error("uartRxPin", "UART TX and UART RX pins must be different."));
        }
    }

    private static void registerPin(Map<Integer, List<String>> uses, int pin, String field) {
        uses.computeIfAbsent(pin, ignored -> new ArrayList<>()).add(field);
    }

    public record DraftInput(
        String enginePulsePin,
        String enginePulsesPerRev,
        EngineEdgeMode engineEdgeMode,
        String encoderPin,
        String encoderPpr,
        String canRxPin,
        String canTxPin,
        String canBitrate,
        String uartTxPin,
        String uartRxPin,
        String uartBaud,
        String telemetryRateHz
    ) {
    }

    public record ValidationResult(
        List<ValidationIssue> issues,
        Optional<Esp32DaqConfigUpdateRequestDto> parsedConfig
    ) {
        public boolean valid() {
            return issues.stream().noneMatch(issue -> issue.severity() == ValidationSeverity.ERROR);
        }

        public List<ValidationIssue> errors() {
            return issues.stream().filter(issue -> issue.severity() == ValidationSeverity.ERROR).toList();
        }

        public List<ValidationIssue> warnings() {
            return issues.stream().filter(issue -> issue.severity() == ValidationSeverity.WARNING).toList();
        }
    }

    public enum ValidationSeverity {
        ERROR,
        WARNING
    }

    public record ValidationIssue(
        String field,
        ValidationSeverity severity,
        String message
    ) {
        public static ValidationIssue error(String field, String message) {
            return new ValidationIssue(field, ValidationSeverity.ERROR, message);
        }

        public static ValidationIssue warning(String field, String message) {
            return new ValidationIssue(field, ValidationSeverity.WARNING, message);
        }
    }
}
