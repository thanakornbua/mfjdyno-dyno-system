package com.dyno.calibration;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RollerInertiaCalculatorTest {
    private static final double EPSILON = 1e-9;

    @Test
    public void solidCylinderMatchesKnownValue() {
        // m=50 kg, r=0.15 m, inner=0 -> I = 0.5 * 50 * 0.15^2 = 0.5625 kg*m^2
        RollerInertiaCalculator.Cylinder solid = new RollerInertiaCalculator.Cylinder(50.0, 0.15, 0.0);

        assertEquals(0.5625, RollerInertiaCalculator.cylinderInertia(solid), EPSILON);
    }

    @Test
    public void hollowCylinderMatchesKnownValue() {
        // m=20 kg, r_out=0.2 m, r_in=0.1 m -> I = 0.5 * 20 * (0.04 + 0.01) = 0.5
        RollerInertiaCalculator.Cylinder hollow = new RollerInertiaCalculator.Cylinder(20.0, 0.2, 0.1);

        assertEquals(0.5, RollerInertiaCalculator.cylinderInertia(hollow), EPSILON);
    }

    @Test
    public void totalInertiaSumsTwoCylinders() {
        RollerInertiaCalculator.Cylinder solid = new RollerInertiaCalculator.Cylinder(50.0, 0.15, 0.0);
        RollerInertiaCalculator.Cylinder hollow = new RollerInertiaCalculator.Cylinder(20.0, 0.2, 0.1);
        List<RollerInertiaCalculator.Cylinder> cylinders = Arrays.asList(solid, hollow);

        assertEquals(1.0625, RollerInertiaCalculator.totalInertia(cylinders), EPSILON);
    }

    @Test
    public void totalInertiaOfEmptyListIsZero() {
        assertEquals(0.0, RollerInertiaCalculator.totalInertia(Collections.<RollerInertiaCalculator.Cylinder>emptyList()), EPSILON);
    }

    @Test
    public void validMassPasses() {
        RollerInertiaCalculator.Cylinder valid = new RollerInertiaCalculator.Cylinder(10.0, 0.1, 0.05);

        assertTrue(RollerInertiaCalculator.validate(1, valid).isEmpty());
    }

    @Test
    public void nonPositiveMassFails() {
        RollerInertiaCalculator.Cylinder zeroMass = new RollerInertiaCalculator.Cylinder(0.0, 0.1, 0.05);

        List<String> errors = RollerInertiaCalculator.validate(1, zeroMass);
        assertEquals(1, errors.size());
        assertEquals("cylinder 1: mass must be greater than zero", errors.get(0));
    }

    @Test
    public void nonPositiveOuterRadiusFails() {
        RollerInertiaCalculator.Cylinder zeroOuter = new RollerInertiaCalculator.Cylinder(10.0, 0.0, 0.0);

        List<String> errors = RollerInertiaCalculator.validate(1, zeroOuter);
        assertTrue(errors.contains("cylinder 1: outer radius must be greater than zero"));
    }

    @Test
    public void negativeInnerRadiusFails() {
        RollerInertiaCalculator.Cylinder negativeInner = new RollerInertiaCalculator.Cylinder(10.0, 0.2, -0.01);

        List<String> errors = RollerInertiaCalculator.validate(1, negativeInner);
        assertTrue(errors.contains("cylinder 1: inner radius must not be negative"));
    }

    @Test
    public void innerRadiusNotSmallerThanOuterFails() {
        RollerInertiaCalculator.Cylinder invalid = new RollerInertiaCalculator.Cylinder(10.0, 0.1, 0.1);

        List<String> errors = RollerInertiaCalculator.validate(2, invalid);
        assertEquals(1, errors.size());
        assertEquals("cylinder 2: inner radius must be smaller than outer radius", errors.get(0));
    }

    @Test
    public void validateAllAggregatesAcrossRows() {
        RollerInertiaCalculator.Cylinder valid = new RollerInertiaCalculator.Cylinder(10.0, 0.1, 0.05);
        RollerInertiaCalculator.Cylinder invalid = new RollerInertiaCalculator.Cylinder(10.0, 0.1, 0.1);

        List<String> errors = RollerInertiaCalculator.validateAll(Arrays.asList(valid, invalid));
        assertEquals(1, errors.size());
        assertEquals("cylinder 2: inner radius must be smaller than outer radius", errors.get(0));
    }
}
