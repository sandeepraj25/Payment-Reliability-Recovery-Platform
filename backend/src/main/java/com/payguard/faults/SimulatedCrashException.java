package com.payguard.faults;

/**
 * Thrown at an armed {@link FaultPoint}. Callers treat it like the process dying at that line:
 * no failure handling, no ack - exactly what the broker / database would see after a crash.
 */
public class SimulatedCrashException extends RuntimeException {

    private final FaultPoint point;

    public SimulatedCrashException(FaultPoint point) {
        super("Simulated crash at " + point);
        this.point = point;
    }

    public FaultPoint point() {
        return point;
    }
}
