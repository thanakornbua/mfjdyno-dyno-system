package com.dyno.calibration;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure physics/validation helper for the multi-roller inertia calculator
 * in the calibration dialog. No JavaFX dependency so it can be unit tested
 * in isolation and reused outside the UI layer.
 */
public final class RollerInertiaCalculator {
    private RollerInertiaCalculator() {
    }

    public static final class Cylinder {
        private final double massKg;
        private final double outerRadiusM;
        private final double innerRadiusM;

        public Cylinder(double massKg, double outerRadiusM, double innerRadiusM) {
            this.massKg = massKg;
            this.outerRadiusM = outerRadiusM;
            this.innerRadiusM = innerRadiusM;
        }

        public double getMassKg() {
            return massKg;
        }

        public double getOuterRadiusM() {
            return outerRadiusM;
        }

        public double getInnerRadiusM() {
            return innerRadiusM;
        }
    }

    /** Hollow cylinder about its axis: I = 0.5 * m * (r_out^2 + r_in^2), kg*m^2. */
    public static double cylinderInertia(Cylinder cylinder) {
        return 0.5 * cylinder.getMassKg()
            * (cylinder.getOuterRadiusM() * cylinder.getOuterRadiusM()
                + cylinder.getInnerRadiusM() * cylinder.getInnerRadiusM());
    }

    public static double totalInertia(List<Cylinder> cylinders) {
        double total = 0.0;
        for (Cylinder cylinder : cylinders) {
            total += cylinderInertia(cylinder);
        }
        return total;
    }

    /**
     * Validates one cylinder row, returning a human-readable error per
     * failure (empty list if valid). {@code rowNumber} is 1-based, used only
     * for the error message prefix.
     */
    public static List<String> validate(int rowNumber, Cylinder cylinder) {
        List<String> errors = new ArrayList<String>();
        String prefix = "cylinder " + rowNumber + ": ";
        if (!(cylinder.getMassKg() > 0.0)) {
            errors.add(prefix + "mass must be greater than zero");
        }
        if (!(cylinder.getOuterRadiusM() > 0.0)) {
            errors.add(prefix + "outer radius must be greater than zero");
        }
        if (cylinder.getInnerRadiusM() < 0.0) {
            errors.add(prefix + "inner radius must not be negative");
        }
        if (cylinder.getInnerRadiusM() >= cylinder.getOuterRadiusM()) {
            errors.add(prefix + "inner radius must be smaller than outer radius");
        }
        return errors;
    }

    public static List<String> validateAll(List<Cylinder> cylinders) {
        List<String> errors = new ArrayList<String>();
        for (int i = 0; i < cylinders.size(); i++) {
            errors.addAll(validate(i + 1, cylinders.get(i)));
        }
        return errors;
    }
}
