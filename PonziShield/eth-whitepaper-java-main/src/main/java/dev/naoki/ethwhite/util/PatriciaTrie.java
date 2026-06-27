package dev.naoki.ethwhite.util;

import dev.naoki.ethwhite.crypto.Keccak;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PatriciaTrie {
    private static final byte[] EMPTY_STRING = Rlp.encodeBytes(new byte[0]);
    private static final byte[] EMPTY_TRIE_ROOT = Keccak.hash(EMPTY_STRING);

    private PatriciaTrie() {
    }

    public static byte[] emptyRoot() {
        return Arrays.copyOf(EMPTY_TRIE_ROOT, EMPTY_TRIE_ROOT.length);
    }

    public static byte[] root(List<Entry> entries) {
        if (entries.isEmpty()) {
            return emptyRoot();
        }
        List<TrieEntry> trieEntries = new ArrayList<>();
        for (Entry entry : entries) {
            trieEntries.add(new TrieEntry(toNibbles(entry.key()), entry.value()));
        }
        Node root = build(trieEntries);
        return reference(root);
    }

    private static Node build(List<TrieEntry> entries) {
        if (entries.isEmpty()) {
            return NullNode.INSTANCE;
        }
        if (entries.size() == 1) {
            TrieEntry only = entries.getFirst();
            return new LeafNode(only.path(), only.value());
        }

        int sharedPrefix = sharedPrefixLength(entries);
        if (sharedPrefix > 0) {
            List<TrieEntry> stripped = new ArrayList<>(entries.size());
            for (TrieEntry entry : entries) {
                stripped.add(new TrieEntry(slice(entry.path(), sharedPrefix), entry.value()));
            }
            return new ExtensionNode(slice(entries.getFirst().path(), 0, sharedPrefix), build(stripped));
        }

        Node[] children = new Node[16];
        byte[] value = null;
        for (int nibble = 0; nibble < 16; nibble++) {
            List<TrieEntry> bucket = new ArrayList<>();
            for (TrieEntry entry : entries) {
                if (entry.path().length == 0) {
                    value = entry.value();
                } else if (entry.path()[0] == nibble) {
                    bucket.add(new TrieEntry(slice(entry.path(), 1), entry.value()));
                }
            }
            children[nibble] = bucket.isEmpty() ? NullNode.INSTANCE : build(bucket);
        }
        return new BranchNode(children, value);
    }

    private static int sharedPrefixLength(List<TrieEntry> entries) {
        int limit = Integer.MAX_VALUE;
        for (TrieEntry entry : entries) {
            limit = Math.min(limit, entry.path().length);
        }
        int prefix = 0;
        outer:
        while (prefix < limit) {
            int candidate = entries.getFirst().path()[prefix];
            for (TrieEntry entry : entries) {
                if (entry.path()[prefix] != candidate) {
                    break outer;
                }
            }
            prefix++;
        }
        return prefix;
    }

    private static byte[] reference(Node node) {
        byte[] encoded = node.encode();
        return encoded.length < 32 ? encoded : Keccak.hash(encoded);
    }

    private static byte[] compactEncode(int[] path, boolean terminator) {
        int flags = terminator ? 2 : 0;
        boolean odd = (path.length & 1) == 1;
        int[] nibbles;
        if (odd) {
            nibbles = new int[path.length + 1];
            nibbles[0] = flags + 1;
            System.arraycopy(path, 0, nibbles, 1, path.length);
        } else {
            nibbles = new int[path.length + 2];
            nibbles[0] = flags;
            nibbles[1] = 0;
            System.arraycopy(path, 0, nibbles, 2, path.length);
        }
        byte[] out = new byte[(nibbles.length + 1) / 2];
        for (int i = 0; i < nibbles.length; i += 2) {
            out[i / 2] = (byte) ((nibbles[i] << 4) | nibbles[i + 1]);
        }
        return out;
    }

    private static int[] toNibbles(byte[] bytes) {
        int[] nibbles = new int[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            nibbles[i * 2] = (bytes[i] >>> 4) & 0x0f;
            nibbles[i * 2 + 1] = bytes[i] & 0x0f;
        }
        return nibbles;
    }

    private static int[] slice(int[] input, int from) {
        return slice(input, from, input.length);
    }

    private static int[] slice(int[] input, int from, int to) {
        return Arrays.copyOfRange(input, from, to);
    }

    public record Entry(byte[] key, byte[] value) {
        public Entry {
            key = Arrays.copyOf(key, key.length);
            value = Arrays.copyOf(value, value.length);
        }
    }

    private record TrieEntry(int[] path, byte[] value) {
        private TrieEntry {
            path = Arrays.copyOf(path, path.length);
            value = Arrays.copyOf(value, value.length);
        }
    }

    private sealed interface Node permits NullNode, LeafNode, ExtensionNode, BranchNode {
        byte[] encode();
    }

    private enum NullNode implements Node {
        INSTANCE;

        @Override
        public byte[] encode() {
            return EMPTY_STRING;
        }
    }

    private record LeafNode(int[] path, byte[] value) implements Node {
        @Override
        public byte[] encode() {
            return Rlp.encodeList(
                    Rlp.encodeBytes(compactEncode(path, true)),
                    Rlp.encodeBytes(value)
            );
        }
    }

    private record ExtensionNode(int[] path, Node child) implements Node {
        @Override
        public byte[] encode() {
            return Rlp.encodeList(
                    Rlp.encodeBytes(compactEncode(path, false)),
                    Rlp.encodeBytes(reference(child))
            );
        }
    }

    private record BranchNode(Node[] children, byte[] value) implements Node {
        @Override
        public byte[] encode() {
            byte[][] items = new byte[17][];
            for (int i = 0; i < 16; i++) {
                items[i] = children[i] == NullNode.INSTANCE
                        ? EMPTY_STRING
                        : Rlp.encodeBytes(reference(children[i]));
            }
            items[16] = Rlp.encodeBytes(value == null ? new byte[0] : value);
            return Rlp.encodeList(items);
        }
    }
}
