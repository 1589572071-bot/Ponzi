package dev.naoki.ethwhite.ponzi;

import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.util.Hex;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable record representing a single fund-flow event emitted by the
 * Ponzi contract.  The {@code eventType} field allows downstream
 * analyzers to distinguish stakes, withdrawals, referral rewards, and
 * dividends without re‑parsing the transaction trace.
 *
 * <p>JSON serialisation is kept minimal and backward‑compatible: every
 * existing field is emitted exactly as before; {@code event_type} is
 * appended as an additional key so that older consumers simply ignore it.
 */
public record FundFlowEvent(
        Address from,
        Address to,
        BigInteger value,
        long blockNumber,
        byte[] txHash,
        long timestamp,
        EventType eventType
) {
    /**
     * Convenience constructor that defaults {@code eventType} to
     * {@link EventType#TRANSFER} – restores the original six‑argument
     * call shape so that no existing caller is forced to change immediately.
     */
    public FundFlowEvent(
            Address from,
            Address to,
            BigInteger value,
            long blockNumber,
            byte[] txHash,
            long timestamp
    ) {
        this(from, to, value, blockNumber, txHash, timestamp,
                EventType.TRANSFER);
    }

    public FundFlowEvent {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(value, "value");
        txHash = Arrays.copyOf(
                Objects.requireNonNull(txHash, "txHash"), txHash.length);
        Objects.requireNonNull(eventType, "eventType");
    }

    @Override
    public byte[] txHash() {
        return Arrays.copyOf(txHash, txHash.length);
    }

    /**
     * Serialise to a single‑line JSON object.
     *
     * <p>The output is backward‑compatible: all six original keys appear
     * exactly as before, and the new {@code "event_type"} key is
     * appended at the end.  Python consumers that ignore unknown keys
     * (including the current {@code transfer_graph.py}) are therefore
     * unaffected.
     */
    public String toJson() {
        return "{"
                + "\"from\":\"" + from.toHex() + "\","
                + "\"to\":\"" + to.toHex() + "\","
                + "\"value\":\"" + value + "\","
                + "\"block_number\":" + blockNumber + ","
                + "\"tx_hash\":\"" + Hex.prefixed(txHash) + "\","
                + "\"timestamp\":" + timestamp + ","
                + "\"event_type\":\"" + eventType.name() + "\""
                + "}";
    }
}
