package dev.naoki.ethwhite.util;

import dev.naoki.ethwhite.crypto.Keccak;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Merkle {
    private Merkle() {
    }

    public static byte[] leaf(byte[] value) {
        return Keccak.hash(value);
    }

    public static byte[] root(List<byte[]> leaves) {
        if (leaves.isEmpty()) {
            return Keccak.hash(new byte[0]);
        }
        List<byte[]> level = new ArrayList<>();
        for (byte[] leaf : leaves) {
            level.add(leaf(leaf));
        }
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                byte[] left = level.get(i);
                byte[] right = level.get(Math.min(i + 1, level.size() - 1));
                next.add(Keccak.hash(Bytes.concat(left, right)));
            }
            level = next;
        }
        return level.getFirst();
    }

    public static boolean verify(byte[] leaf, List<byte[]> siblings, List<Integer> pathBits, byte[] expectedRoot) {
        if (siblings.size() != pathBits.size()) {
            return false;
        }
        byte[] current = leaf(leaf);
        for (int i = 0; i < siblings.size(); i++) {
            if (pathBits.get(i) == 0) {
                current = Keccak.hash(Bytes.concat(current, siblings.get(i)));
            } else {
                current = Keccak.hash(Bytes.concat(siblings.get(i), current));
            }
        }
        return Arrays.equals(current, expectedRoot);
    }
}
