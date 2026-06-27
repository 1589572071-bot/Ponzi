package dev.naoki.ethwhite.util;

public final class Hex {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    public static String encode(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }

    public static String prefixed(byte[] bytes) {
        return "0x" + encode(bytes);
    }

    public static byte[] decode(String hex) {
        String normalized = stripPrefix(hex);
        if ((normalized.length() & 1) == 1) {
            normalized = "0" + normalized;
        }
        byte[] out = new byte[normalized.length() / 2];
        for (int i = 0; i < normalized.length(); i += 2) {
            int hi = Character.digit(normalized.charAt(i), 16);
            int lo = Character.digit(normalized.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex: " + hex);
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String stripPrefix(String hex) {
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            return hex.substring(2);
        }
        return hex;
    }
}
