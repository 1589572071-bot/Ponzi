package dev.naoki.ethwhite.core;

public record BlockContext(
        long number,
        long timestamp,
        byte[] previousBlockHash,
        Address miner,
        long gasLimit,
        long difficulty
) {
}
