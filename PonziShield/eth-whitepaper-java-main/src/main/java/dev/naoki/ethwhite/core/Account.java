package dev.naoki.ethwhite.core;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public final class Account {
    private long nonce;
    private BigInteger balance;
    private AccountType type;
    private byte[] code;
    private String contractId;
    private final NavigableMap<BigInteger, Word> storage;
    private final NavigableMap<String, byte[]> metadata;

    public Account() {
        this(0L, BigInteger.ZERO, AccountType.EXTERNALLY_OWNED, new byte[0], null, new TreeMap<>(), new TreeMap<>());
    }

    public Account(long nonce, BigInteger balance, AccountType type, byte[] code, String contractId,
                   NavigableMap<BigInteger, Word> storage, NavigableMap<String, byte[]> metadata) {
        this.nonce = nonce;
        this.balance = requireNonNegative(balance, "balance");
        this.type = Objects.requireNonNull(type, "type");
        this.code = Arrays.copyOf(code, code.length);
        this.contractId = contractId;
        this.storage = new TreeMap<>(storage);
        this.metadata = deepCopy(metadata);
    }

    public Account copy() {
        return new Account(nonce, balance, type, code, contractId, storage, metadata);
    }

    private static BigInteger requireNonNegative(BigInteger value, String label) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(label + " must not be negative");
        }
        return value;
    }

    private static NavigableMap<String, byte[]> deepCopy(NavigableMap<String, byte[]> input) {
        NavigableMap<String, byte[]> out = new TreeMap<>();
        input.forEach((key, value) -> out.put(key, Arrays.copyOf(value, value.length)));
        return out;
    }

    public long nonce() {
        return nonce;
    }

    public void setNonce(long nonce) {
        if (nonce < 0) {
            throw new IllegalArgumentException("Nonce must be non-negative");
        }
        this.nonce = nonce;
    }

    public void incrementNonce() {
        nonce = Math.incrementExact(nonce);
    }

    public BigInteger balance() {
        return balance;
    }

    public void setBalance(BigInteger balance) {
        this.balance = requireNonNegative(balance, "balance");
    }

    public void credit(BigInteger amount) {
        this.balance = balance.add(requireNonNegative(amount, "amount"));
    }

    public void debit(BigInteger amount) {
        BigInteger normalized = requireNonNegative(amount, "amount");
        if (balance.compareTo(normalized) < 0) {
            throw new IllegalStateException("Insufficient balance");
        }
        this.balance = balance.subtract(normalized);
    }

    public AccountType type() {
        return type;
    }

    public void setType(AccountType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public byte[] code() {
        return Arrays.copyOf(code, code.length);
    }

    public void setCode(byte[] code) {
        this.code = Arrays.copyOf(code, code.length);
    }

    public String contractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public NavigableMap<BigInteger, Word> storage() {
        return storage;
    }

    public NavigableMap<String, byte[]> metadata() {
        return metadata;
    }
}
