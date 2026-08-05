package com.sumirelabs.lightbench;

/** Raw observations for one fixed-position light-update phase. */
final class UpdatePhaseResult {

    final String name;
    final String lightType;
    final String action;
    final int x;
    final int y;
    final int z;
    final long[] submissionNanos;
    final long[] barrierNanos;
    final long[] completionNanos;

    UpdatePhaseResult(
            final String name,
            final String lightType,
            final String action,
            final int x,
            final int y,
            final int z,
            final long[] submissionNanos,
            final long[] barrierNanos,
            final long[] completionNanos) {
        final int sampleCount = completionNanos.length;
        if (sampleCount == 0 || submissionNanos.length != sampleCount || barrierNanos.length != sampleCount) {
            throw new IllegalArgumentException("update sample columns must have the same non-zero length");
        }
        for (int index = 0; index < sampleCount; ++index) {
            if (submissionNanos[index] < 0
                    || barrierNanos[index] < 0
                    || completionNanos[index] < submissionNanos[index]
                    || completionNanos[index] < barrierNanos[index]) {
                throw new IllegalArgumentException(
                        "update timings must be non-negative and fit within completion time");
            }
        }
        this.name = name;
        this.lightType = lightType;
        this.action = action;
        this.x = x;
        this.y = y;
        this.z = z;
        this.submissionNanos = submissionNanos;
        this.barrierNanos = barrierNanos;
        this.completionNanos = completionNanos;
    }

    int size() {
        return this.completionNanos.length;
    }
}
