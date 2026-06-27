package dev.naoki.ethwhite.ponzi;

import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.util.Hex;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

public record FundFlowEvent(
        Address from,
        Address to,
        BigInteger value,
        long blockNumber,
        byte[] txHash,
        long timestamp
) {
    public FundFlowEvent {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(value, "value");
        txHash = Arrays.copyOf(Objects.requireNonNull(txHash, "txHash"), txHash.length);
    }

    @Override
    public byte[] txHash() {
        return Arrays.copyOf(txHash, txHash.length);
    }

    public String toJson() {
        return "{"
                + "\"from\":\"" + from.toHex() + "\","
                + "\"to\":\"" + to.toHex() + "\","
                + "\"value\":\"" + value + "\","
                + "\"block_number\":" + blockNumber + ","
                + "\"tx_hash\":\"" + Hex.prefixed(txHash) + "\","
                + "\"timestamp\":" + timestamp
                + "}";
    }
}
