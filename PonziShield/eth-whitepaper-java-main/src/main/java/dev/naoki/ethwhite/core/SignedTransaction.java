package dev.naoki.ethwhite.core;

import dev.naoki.ethwhite.crypto.Keccak;
import dev.naoki.ethwhite.crypto.Secp256k1;
import dev.naoki.ethwhite.crypto.Wallet;

import java.util.Arrays;
import java.util.Objects;

public final class SignedTransaction {
    private final Transaction transaction;
    private final byte[] publicKey;
    private final byte[] signature;

    public SignedTransaction(Transaction transaction, byte[] publicKey, byte[] signature) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.publicKey = Arrays.copyOf(publicKey, publicKey.length);
        this.signature = Arrays.copyOf(signature, signature.length);
    }

    public static SignedTransaction sign(Transaction transaction, Wallet wallet) {
        byte[] signingHash = Keccak.hash(transaction.signingPayload());
        return new SignedTransaction(transaction, wallet.publicKey(), wallet.signTransaction(signingHash));
    }

    public Transaction transaction() {
        return transaction;
    }

    public byte[] publicKey() {
        return Arrays.copyOf(publicKey, publicKey.length);
    }

    public byte[] signature() {
        return Arrays.copyOf(signature, signature.length);
    }

    public Address sender() {
        return Secp256k1.address(publicKey);
    }

    public boolean verify() {
        return Secp256k1.verify(Keccak.hash(transaction.signingPayload()), signature, publicKey);
    }

    public byte[] encode() {
        return transaction.encodeSignedEnvelope(publicKey, signature);
    }
}
