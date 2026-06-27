package dev.naoki.ethwhite.util;

import dev.naoki.ethwhite.core.Address;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class Rlp {
    private Rlp() {
    }

    public static byte[] encodeBytes(byte[] value) {
        if (value.length == 1 && Byte.toUnsignedInt(value[0]) < 0x80) {
            return value.clone();
        }
        return Bytes.concat(encodeLength(value.length, 0x80), value);
    }

    public static byte[] encodeString(String value) {
        return encodeBytes(Bytes.ofString(value));
    }

    public static byte[] encodeLong(long value) {
        if (value == 0) {
            return encodeBytes(new byte[0]);
        }
        return encodeBigInteger(BigInteger.valueOf(value));
    }

    public static byte[] encodeBigInteger(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("RLP does not encode negative integers");
        }
        if (value.signum() == 0) {
            return encodeBytes(new byte[0]);
        }
        return encodeBytes(trimLeadingZeroes(value.toByteArray()));
    }

    public static byte[] encodeAddress(Address address) {
        return encodeBytes(address == null ? new byte[0] : address.toBytes());
    }

    public static byte[] encodeList(Collection<byte[]> items) {
        return encodeList(items.toArray(byte[][]::new));
    }

    public static byte[] encodeList(byte[]... items) {
        byte[] payload = Bytes.concat(items);
        return Bytes.concat(encodeLength(payload.length, 0xc0), payload);
    }

    public static byte[] encodeScalarBytes(byte[] value) {
        return encodeBytes(trimLeadingZeroes(value));
    }

    private static byte[] encodeLength(int length, int offset) {
        if (length < 56) {
            return new byte[] {(byte) (offset + length)};
        }
        byte[] lengthBytes = trimLeadingZeroes(BigInteger.valueOf(length).toByteArray());
        return Bytes.concat(new byte[] {(byte) (offset + 55 + lengthBytes.length)}, lengthBytes);
    }

    private static byte[] trimLeadingZeroes(byte[] input) {
        int index = 0;
        while (index < input.length - 1 && input[index] == 0) {
            index++;
        }
        byte[] out = new byte[input.length - index];
        System.arraycopy(input, index, out, 0, out.length);
        return out;
    }
}
