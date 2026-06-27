package dev.naoki.ethwhite.core;

import dev.naoki.ethwhite.crypto.Keccak;
import dev.naoki.ethwhite.util.Rlp;

import java.math.BigInteger;

public record BlockHeader(
        byte[] parentHash,
        byte[] stateRoot,
        byte[] transactionsRoot,
        byte[] unclesRoot,
        Address miner,
        long number,
        long timestamp,
        long difficulty,
        long gasLimit,
        long gasUsed,
        long nonce
) {
    private static final BigInteger MAX_TARGET = BigInteger.ONE.shiftLeft(255);

    public byte[] encode() {
        return Rlp.encodeList(
                Rlp.encodeBytes(parentHash),
                Rlp.encodeBytes(stateRoot),
                Rlp.encodeBytes(transactionsRoot),
                Rlp.encodeBytes(unclesRoot),
                Rlp.encodeAddress(miner),
                Rlp.encodeLong(number),
                Rlp.encodeLong(timestamp),
                Rlp.encodeLong(difficulty),
                Rlp.encodeLong(gasLimit),
                Rlp.encodeLong(gasUsed),
                Rlp.encodeLong(nonce)
        );
    }

    public byte[] hash() {
        return Keccak.hash(encode());
    }

    public byte[] hashWithoutNonce() {
        return Keccak.hash(Rlp.encodeList(
                Rlp.encodeBytes(parentHash),
                Rlp.encodeBytes(stateRoot),
                Rlp.encodeBytes(transactionsRoot),
                Rlp.encodeBytes(unclesRoot),
                Rlp.encodeAddress(miner),
                Rlp.encodeLong(number),
                Rlp.encodeLong(timestamp),
                Rlp.encodeLong(difficulty),
                Rlp.encodeLong(gasLimit),
                Rlp.encodeLong(gasUsed)
        ));
    }

    public boolean validProofOfWork() {
        if (difficulty <= 0) {
            return false;
        }
        BigInteger target = MAX_TARGET.divide(BigInteger.valueOf(difficulty));
        BigInteger value = new BigInteger(1, hash());
        return value.compareTo(target) <= 0;
    }

    public BlockContext blockContext() {
        return new BlockContext(number, timestamp, parentHash, miner, gasLimit, difficulty);
    }
}
