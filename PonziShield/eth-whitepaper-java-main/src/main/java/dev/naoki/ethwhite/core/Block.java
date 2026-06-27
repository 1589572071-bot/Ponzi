package dev.naoki.ethwhite.core;

import java.util.List;

public record Block(BlockHeader header, List<SignedTransaction> transactions, List<BlockHeader> uncles) {
    public Block {
        transactions = List.copyOf(transactions);
        uncles = List.copyOf(uncles);
    }
}
