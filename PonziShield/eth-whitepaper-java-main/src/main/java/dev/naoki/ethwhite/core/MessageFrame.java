package dev.naoki.ethwhite.core;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

public final class MessageFrame {
    private final Address sender;
    private final Address recipient;
    private final BigInteger value;
    private final byte[] data;
    private final GasMeter gasMeter;
    private final int depth;

    public MessageFrame(Address sender, Address recipient, BigInteger value, byte[] data, long gasLimit, int depth) {
        if (gasLimit < 0 || depth < 0 || value.signum() < 0) {
            throw new IllegalArgumentException("Invalid message frame values");
        }
        this.sender = Objects.requireNonNull(sender, "sender");
        this.recipient = Objects.requireNonNull(recipient, "recipient");
        this.value = value;
        this.data = Arrays.copyOf(data, data.length);
        this.gasMeter = new GasMeter(gasLimit);
        this.depth = depth;
    }

    public Address sender() {
        return sender;
    }

    public Address recipient() {
        return recipient;
    }

    public BigInteger value() {
        return value;
    }

    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    public GasMeter gasMeter() {
        return gasMeter;
    }

    public int depth() {
        return depth;
    }
}
