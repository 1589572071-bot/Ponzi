package dev.naoki.ethwhite;

import dev.naoki.ethwhite.contract.ContractRegistry;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.Block;
import dev.naoki.ethwhite.core.BlockContext;
import dev.naoki.ethwhite.core.BlockHeader;
import dev.naoki.ethwhite.core.BlockProcessor;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.ProofOfWork;
import dev.naoki.ethwhite.core.SignedTransaction;
import dev.naoki.ethwhite.core.Transaction;
import dev.naoki.ethwhite.core.TransactionProcessor;
import dev.naoki.ethwhite.core.TransactionReceipt;
import dev.naoki.ethwhite.core.WorldState;
import dev.naoki.ethwhite.crypto.Wallet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

final class TestSupport {
    private TestSupport() {
    }

    static TransactionReceipt apply(TransactionProcessor processor, WorldState state, Wallet wallet, Transaction transaction,
                                    long blockNumber, long timestamp, Address miner) {
        return processor.apply(state, SignedTransaction.sign(transaction, wallet),
                new BlockContext(blockNumber, timestamp, new byte[32], miner, 1_000_000L, 1L));
    }

    static Block mineBlock(Block parent, WorldState parentState, Address miner, List<SignedTransaction> transactions,
                           List<BlockHeader> uncles, ContractRegistry registry, long timestamp) {
        TransactionProcessor processor = new TransactionProcessor(registry);
        WorldState working = parentState.copy();
        long number = parent == null ? 0 : parent.header().number() + 1;
        byte[] parentHash = parent == null ? new byte[32] : parent.header().hash();
        long gasUsed = 0;
        BlockContext context = new BlockContext(number, timestamp, parentHash, miner, 1_000_000L, 1L);
        for (SignedTransaction transaction : transactions) {
            TransactionReceipt receipt = processor.apply(working, transaction, context);
            gasUsed += receipt.gasUsed();
        }
        working.getOrCreate(miner).credit(BlockProcessor.BASE_BLOCK_REWARD);
        for (BlockHeader uncle : uncles) {
            working.getOrCreate(uncle.miner()).credit(BlockProcessor.UNCLE_REWARD);
            working.getOrCreate(miner).credit(BlockProcessor.NEPHEW_REWARD);
        }
        BlockHeader header = ProofOfWork.mine(new BlockHeader(
                parentHash,
                working.stateRoot(),
                BlockProcessor.transactionsRoot(transactions),
                BlockProcessor.unclesRoot(uncles),
                miner,
                number,
                timestamp,
                1L,
                1_000_000L,
                gasUsed,
                0L
        ), 10_000L);
        return new Block(header, transactions, uncles);
    }

    static byte[] initCodeReturning(byte[] runtimeCode) {
        if (runtimeCode.length == 0 || runtimeCode.length > 255) {
            throw new IllegalArgumentException("Helper supports runtime code of 1..255 bytes");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {
                    0x60, (byte) runtimeCode.length,
                    0x60, 0x0c,
                    0x60, 0x00,
                    0x39,
                    0x60, (byte) runtimeCode.length,
                    0x60, 0x00,
                    (byte) 0xf3
            });
            out.write(runtimeCode);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unexpected byte stream error", exception);
        }
    }
}
