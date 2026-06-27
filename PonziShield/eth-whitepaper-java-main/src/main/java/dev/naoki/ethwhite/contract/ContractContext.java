package dev.naoki.ethwhite.contract;

import dev.naoki.ethwhite.core.Account;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.BlockContext;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.GasMeter;
import dev.naoki.ethwhite.core.MessageResult;
import dev.naoki.ethwhite.core.ExecutionException;
import dev.naoki.ethwhite.core.Word;
import dev.naoki.ethwhite.core.WorldState;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class ContractContext {
    private final WorldState state;
    private final BlockContext block;
    private final MessageDispatcher dispatcher;
    private final Address self;
    private final Address sender;
    private final Address origin;
    private final BigInteger value;
    private final byte[] data;
    private final GasMeter gasMeter;
    private final int depth;

    public ContractContext(WorldState state, BlockContext block, MessageDispatcher dispatcher, Address self,
                           Address sender, Address origin, BigInteger value, byte[] data, GasMeter gasMeter, int depth) {
        this.state = Objects.requireNonNull(state, "state");
        this.block = Objects.requireNonNull(block, "block");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.self = Objects.requireNonNull(self, "self");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.value = Objects.requireNonNull(value, "value");
        this.data = Arrays.copyOf(data, data.length);
        this.gasMeter = Objects.requireNonNull(gasMeter, "gasMeter");
        this.depth = depth;
    }

    public WorldState state() {
        return state;
    }

    public BlockContext block() {
        return block;
    }

    public Address self() {
        return self;
    }

    public Address sender() {
        return sender;
    }

    public Address origin() {
        return origin;
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

    public Account account() {
        return state.getOrCreate(self);
    }

    public Word storageWord(BigInteger key) {
        return account().storage().getOrDefault(key, Word.ZERO);
    }

    public void putStorageWord(BigInteger key, Word value) {
        gasMeter.consume(20, "writing storage");
        if (value.isZero()) {
            account().storage().remove(key);
        } else {
            account().storage().put(key, value);
        }
    }

    public String metadata(String key) {
        byte[] value = account().metadata().get(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    public byte[] metadataBytes(String key) {
        byte[] value = account().metadata().get(key);
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    public void putMetadata(String key, String value) {
        gasMeter.consume(15, "writing metadata");
        account().metadata().put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    public void putMetadataBytes(String key, byte[] value) {
        gasMeter.consume(15, "writing metadata");
        account().metadata().put(key, Arrays.copyOf(value, value.length));
    }

    public void removeMetadata(String key) {
        gasMeter.consume(10, "removing metadata");
        account().metadata().remove(key);
    }

    public MessageResult call(Address to, BigInteger amount, CallData callData, long gasLimit) {
        reserveGasForChild(gasLimit);
        gasMeter.consume(40, "dispatching sub-call");
        MessageResult result = dispatcher.call(self, to, amount, callData.encode(), gasLimit, depth + 1);
        settleChildGas(gasLimit, result.gasRemaining(), "paying for sub-call execution");
        return result;
    }

    public MessageResult call(Address to, BigInteger amount, byte[] payload, long gasLimit) {
        reserveGasForChild(gasLimit);
        gasMeter.consume(40, "dispatching sub-call");
        MessageResult result = dispatcher.call(self, to, amount, payload, gasLimit, depth + 1);
        settleChildGas(gasLimit, result.gasRemaining(), "paying for sub-call execution");
        return result;
    }

    public ContractCreationResult create(BigInteger amount, byte[] initCode, long gasLimit) {
        reserveGasForChild(gasLimit);
        gasMeter.consume(80, "dispatching contract creation");
        ContractCreationResult result = dispatcher.create(self, amount, initCode, gasLimit, depth + 1);
        settleChildGas(gasLimit, result.gasRemaining(), "paying for contract creation");
        return result;
    }

    private void reserveGasForChild(long gasLimit) {
        if (gasLimit < 0) {
            throw new ExecutionException("Gas limit must not be negative");
        }
        if (gasLimit > gasMeter.remaining()) {
            throw new ExecutionException("Sub-call gas exceeds available gas");
        }
    }

    private void settleChildGas(long gasLimit, long gasRemaining, String reason) {
        long spent = gasLimit - gasRemaining;
        if (spent < 0) {
            throw new ExecutionException("Child call returned invalid gas value");
        }
        if (spent > 0) {
            gasMeter.consume(spent, reason);
        }
    }
}
