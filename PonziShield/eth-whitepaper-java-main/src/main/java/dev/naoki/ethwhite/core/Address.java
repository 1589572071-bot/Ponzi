package dev.naoki.ethwhite.core;

import dev.naoki.ethwhite.crypto.Keccak;
import dev.naoki.ethwhite.util.Hex;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

public final class Address implements Comparable<Address> {
    public static final int SIZE = 20;
    public static final Address ZERO = new Address(new byte[SIZE]);
    private final byte[] bytes;

    private Address(byte[] bytes) {
        this.bytes = bytes;
    }

    public static Address fromBytes(byte[] input) {
        if (input.length != SIZE) {
            throw new IllegalArgumentException("Address must be 20 bytes");
        }
        return new Address(Arrays.copyOf(input, SIZE));
    }

    public static Address fromHex(String hex) {
        return fromBytes(Hex.decode(hex));
    }

    public static Address fromPublicKey(byte[] publicKey) {
        byte[] hash = Keccak.hash(publicKey);
        return fromBytes(Arrays.copyOfRange(hash, hash.length - SIZE, hash.length));
    }

    public static Address random() {
        byte[] bytes = new byte[SIZE];
        new SecureRandom().nextBytes(bytes);
        return new Address(bytes);
    }

    public byte[] toBytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public String toHex() {
        return Hex.prefixed(bytes);
    }

    @Override
    public String toString() {
        return toHex();
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Address address)) {
            return false;
        }
        return Arrays.equals(bytes, address.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(bytes));
    }

    @Override
    public int compareTo(Address other) {
        return Arrays.compareUnsigned(bytes, other.bytes);
    }
}
