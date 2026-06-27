package dev.naoki.ethwhite.core;

import dev.naoki.ethwhite.contract.ContractRegistry;
import dev.naoki.ethwhite.crypto.Keccak;
import dev.naoki.ethwhite.util.PatriciaTrie;
import dev.naoki.ethwhite.util.Rlp;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BlockProcessor {
    public static final BigInteger BASE_BLOCK_REWARD = Units.ETHER.multiply(BigInteger.valueOf(5));
    public static final BigInteger UNCLE_REWARD = BASE_BLOCK_REWARD.multiply(BigInteger.valueOf(15)).divide(BigInteger.valueOf(16));
    public static final BigInteger NEPHEW_REWARD = BASE_BLOCK_REWARD.divide(BigInteger.valueOf(16));

    private final TransactionProcessor transactionProcessor;

    public BlockProcessor(ContractRegistry contractRegistry) {
        this.transactionProcessor = new TransactionProcessor(contractRegistry);
    }

    public WorldState validateAndApply(Block block, Block parent, WorldState parentState, BlockchainIndex index, Set<String> includedUncles) {
        if (parent != null) {
            if (parent.header().timestamp() >= block.header().timestamp()) {
                throw new ExecutionException("Block timestamp must increase");
            }
            long now = System.currentTimeMillis() / 1000L;
            if (block.header().timestamp() > now + 900) {
                throw new ExecutionException("Block timestamp too far in future");
            }
            if (block.header().number() != parent.header().number() + 1) {
                throw new ExecutionException("Invalid block number");
            }
        } else if (block.header().number() != 0) {
            throw new ExecutionException("Genesis block number must be zero");
        }

        if (block.header().difficulty() <= 0 || block.header().gasUsed() > block.header().gasLimit()) {
            throw new ExecutionException("Invalid difficulty or gas usage");
        }
        if (!block.header().validProofOfWork()) {
            throw new ExecutionException("Invalid proof of work");
        }
        if (!equalHash(block.header().transactionsRoot(), transactionsRoot(block.transactions()))) {
            throw new ExecutionException("Transaction root mismatch");
        }
        if (!equalHash(block.header().unclesRoot(), unclesRoot(block.uncles()))) {
            throw new ExecutionException("Uncle root mismatch");
        }

        validateUncles(block, index, includedUncles);

        WorldState working = parentState.copy();
        long totalGasUsed = 0;
        for (SignedTransaction transaction : block.transactions()) {
            TransactionReceipt receipt = transactionProcessor.apply(working, transaction, block.header().blockContext());
            totalGasUsed += receipt.gasUsed();
            if (totalGasUsed > block.header().gasLimit()) {
                throw new ExecutionException("Block gas limit exceeded");
            }
        }
        if (totalGasUsed != block.header().gasUsed()) {
            throw new ExecutionException("Gas used mismatch");
        }

        working.getOrCreate(block.header().miner()).credit(BASE_BLOCK_REWARD);
        for (BlockHeader uncle : block.uncles()) {
            working.getOrCreate(uncle.miner()).credit(UNCLE_REWARD);
            working.getOrCreate(block.header().miner()).credit(NEPHEW_REWARD);
        }
        if (!equalHash(block.header().stateRoot(), working.stateRoot())) {
            throw new ExecutionException("State root mismatch");
        }
        return working;
    }

    private void validateUncles(Block block, BlockchainIndex index, Set<String> includedUncles) {
        Set<String> local = new HashSet<>();
        for (BlockHeader uncle : block.uncles()) {
            String uncleHash = dev.naoki.ethwhite.util.Hex.encode(uncle.hash());
            if (!local.add(uncleHash)) {
                throw new ExecutionException("Duplicate uncle in block");
            }
            if (includedUncles.contains(uncleHash)) {
                throw new ExecutionException("Uncle already included");
            }
            BlockHeader ancestor = index.header(uncle.parentHash());
            if (ancestor == null) {
                throw new ExecutionException("Unknown uncle parent");
            }
            long generationDistance = block.header().number() - uncle.number();
            if (generationDistance < 2 || generationDistance > 5) {
                throw new ExecutionException("Uncle generation distance must be between 2 and 5");
            }
            if (!uncle.validProofOfWork()) {
                throw new ExecutionException("Invalid uncle proof of work");
            }
            if (index.isAncestor(uncle.hash(), block.header().parentHash())) {
                throw new ExecutionException("Canonical ancestors cannot be included as uncles");
            }
        }
    }

    public static byte[] transactionsRoot(List<SignedTransaction> transactions) {
        List<PatriciaTrie.Entry> entries = new ArrayList<>();
        for (int i = 0; i < transactions.size(); i++) {
            SignedTransaction transaction = transactions.get(i);
            entries.add(new PatriciaTrie.Entry(
                    Rlp.encodeLong(i),
                    transaction.encode()
            ));
        }
        return PatriciaTrie.root(entries);
    }

    public static byte[] unclesRoot(List<BlockHeader> uncles) {
        List<byte[]> headers = new ArrayList<>();
        for (BlockHeader uncle : uncles) {
            headers.add(uncle.encode());
        }
        return Keccak.hash(Rlp.encodeList(headers));
    }

    private static boolean equalHash(byte[] left, byte[] right) {
        return java.util.Arrays.equals(left, right);
    }

    public interface BlockchainIndex {
        BlockHeader header(byte[] hash);

        boolean isAncestor(byte[] candidateAncestor, byte[] blockHash);
    }
}
