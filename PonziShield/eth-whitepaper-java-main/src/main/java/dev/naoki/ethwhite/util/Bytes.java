package dev.naoki.ethwhite.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public final class Bytes {
    private Bytes() {
    }

    public static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (byte[] array : arrays) {
                out.write(array);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unexpected byte stream error", exception);
        }
        return out.toByteArray();
    }

    public static byte[] ofLong(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    public static byte[] ofInt(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    public static byte[] ofString(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] ofBigInteger(BigInteger value) {
        byte[] raw = value.toByteArray();
        return raw.length == 1 && raw[0] == 0 ? new byte[0] : raw;
    }

    public static byte[] leftPad(byte[] input, int size) {
        if (input.length > size) {
            throw new IllegalArgumentException("Input larger than requested size");
        }
        byte[] out = new byte[size];
        System.arraycopy(input, 0, out, size - input.length, input.length);
        return out;
    }

    public static byte[] writeLengthPrefixed(byte[] input) {
        return concat(ofInt(input.length), input);
    }

    public static byte[] joinLengthPrefixed(Collection<byte[]> values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (byte[] value : values) {
                out.write(writeLengthPrefixed(value));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unexpected byte stream error", exception);
        }
        return out.toByteArray();
    }
}
