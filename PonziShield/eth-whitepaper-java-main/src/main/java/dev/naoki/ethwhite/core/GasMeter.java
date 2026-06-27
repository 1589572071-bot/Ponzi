package dev.naoki.ethwhite.core;

public final class GasMeter {
    private long remaining;

    public GasMeter(long remaining) {
        if (remaining < 0) {
            throw new IllegalArgumentException("Gas must not be negative");
        }
        this.remaining = remaining;
    }

    public void consume(long amount, String reason) {
        if (amount < 0) {
            throw new IllegalArgumentException("Gas amount must not be negative");
        }
        if (remaining < amount) {
            throw new OutOfGasException("Out of gas while " + reason);
        }
        remaining -= amount;
    }

    public void refund(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Refund gas must not be negative");
        }
        remaining += amount;
    }

    public long remaining() {
        return remaining;
    }
}
