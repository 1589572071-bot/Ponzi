package dev.naoki.ethwhite.core;

import dev.naoki.ethwhite.contract.ContractRegistry;
import dev.naoki.ethwhite.util.Hex;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Blockchain implements BlockProcessor.BlockchainIndex {
    private final BlockProcessor blockProcessor;
    private final Map<String, Block> blocks = new HashMap<>();
    private final Map<String, WorldState> states = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Set<String> includedUncles = new HashSet<>();
    private String genesisHash;
    private String headHash;

    public Blockchain(ContractRegistry contractRegistry) {
        this.blockProcessor = new BlockProcessor(contractRegistry);
    }

    public void addGenesis(Block genesis, WorldState genesisState) {
        if (!blocks.isEmpty()) {
            throw new IllegalStateException("Genesis already set");
        }
        WorldState applied = blockProcessor.validateAndApply(genesis, null, genesisState, this, includedUncles);
        String hash = key(genesis.header().hash());
        blocks.put(hash, genesis);
        states.put(hash, applied);
        genesisHash = hash;
        headHash = hash;
    }

    public void addBlock(Block block) {
        String parentHash = key(block.header().parentHash());
        Block parent = blocks.get(parentHash);
        if (parent == null) {
            throw new ExecutionException("Unknown parent block");
        }
        WorldState parentState = states.get(parentHash);
        WorldState applied = blockProcessor.validateAndApply(block, parent, parentState, this, includedUncles);
        String hash = key(block.header().hash());
        blocks.put(hash, block);
        states.put(hash, applied);
        parents.put(hash, parentHash);
        for (BlockHeader uncle : block.uncles()) {
            includedUncles.add(key(uncle.hash()));
        }
        headHash = selectGhostHead();
    }

    public Block head() {
        return blocks.get(headHash);
    }

    public WorldState headState() {
        return states.get(headHash).copy();
    }

    public WorldState stateAt(Block block) {
        return states.get(key(block.header().hash())).copy();
    }

    public List<Block> chainFromGenesis() {
        Deque<Block> ordered = new ArrayDeque<>();
        String cursor = headHash;
        while (cursor != null) {
            ordered.addFirst(blocks.get(cursor));
            cursor = parents.get(cursor);
        }
        return List.copyOf(ordered);
    }

    @Override
    public BlockHeader header(byte[] hash) {
        Block block = blocks.get(key(hash));
        return block == null ? null : block.header();
    }

    @Override
    public boolean isAncestor(byte[] candidateAncestor, byte[] blockHash) {
        String candidate = key(candidateAncestor);
        String cursor = key(blockHash);
        while (cursor != null) {
            if (cursor.equals(candidate)) {
                return true;
            }
            cursor = parents.get(cursor);
        }
        return false;
    }

    private String selectGhostHead() {
        String cursor = genesisHash;
        while (true) {
            List<String> children = childHashes(cursor);
            if (children.isEmpty()) {
                return cursor;
            }
            cursor = children.stream()
                    .max(Comparator.comparing(this::subtreeWeight).thenComparing(hash -> hash))
                    .orElseThrow();
        }
    }

    private List<String> childHashes(String parentHash) {
        List<String> children = new ArrayList<>();
        for (Map.Entry<String, String> entry : parents.entrySet()) {
            if (entry.getValue().equals(parentHash)) {
                children.add(entry.getKey());
            }
        }
        return children;
    }

    private BigInteger subtreeWeight(String hash) {
        Block block = blocks.get(hash);
        BigInteger total = BigInteger.valueOf(block.header().difficulty());
        for (String child : childHashes(hash)) {
            total = total.add(subtreeWeight(child));
        }
        return total;
    }

    private static String key(byte[] hash) {
        return Hex.encode(hash);
    }
}
