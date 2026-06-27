package dev.naoki.ethwhite.core;

import dev.naoki.ethwhite.util.Bytes;
import dev.naoki.ethwhite.util.Hex;

import java.math.BigInteger;
import java.util.Arrays;

public final class Word implements Comparable<Word> {
    public static final int SIZE = 32;
    public static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(256);
    public static final Word ZERO = new Word(BigInteger.ZERO);
    public static final Word ONE = new Word(BigInteger.ONE);
    private final BigInteger value;

    private Word(BigInteger value) {
        this.value = normalize(value);
    }

    private static BigInteger normalize(BigInteger value) {
        BigInteger normalized = value.mod(MODULUS);
        return normalized.signum() < 0 ? normalized.add(MODULUS) : normalized;
    }

    public static Word of(long value) {
        return new Word(BigInteger.valueOf(value));
    }

    public static Word of(BigInteger value) {
        return new Word(value);
    }

    public static Word fromBytes(byte[] value) {
        return new Word(new BigInteger(1, value));
    }

    public BigInteger toBigInteger() {
        return value;
    }

    public int toIntExact() {
        return value.intValueExact();
    }

    public long toLongExact() {
        return value.longValueExact();
    }

    public byte[] toBytes() {
        return Bytes.leftPad(Bytes.ofBigInteger(value), SIZE);
    }

    public String toHex() {
        return Hex.prefixed(toBytes());
    }

    public Word add(Word other) {
        return new Word(value.add(other.value));
    }

    public Word sub(Word other) {
        return new Word(value.subtract(other.value));
    }

    public Word mul(Word other) {
        return new Word(value.multiply(other.value));
    }

    public Word div(Word other) {
        return other.isZero() ? ZERO : new Word(value.divide(other.value));
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    @Override
    public int compareTo(Word other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Word word)) {
            return false;
        }
        return Arrays.equals(toBytes(), word.toBytes());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(toBytes());
    }

    @Override
    public String toString() {
        return toHex();
    }
}
