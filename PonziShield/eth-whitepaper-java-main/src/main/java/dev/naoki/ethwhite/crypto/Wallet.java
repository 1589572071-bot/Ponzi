package dev.naoki.ethwhite.crypto;

import dev.naoki.ethwhite.core.Address;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.util.Arrays;

public final class Wallet {
    private final KeyPair keyPair;
    private final Address address;

    private Wallet(KeyPair keyPair) {
        this.keyPair = keyPair;
        this.address = Secp256k1.address(publicKey());
    }

    public static Wallet create() {
        return new Wallet(Secp256k1.generate());
    }

    public Address address() {
        return address;
    }

    public byte[] publicKey() {
        return keyPair.getPublic().getEncoded();
    }

    public byte[] sign(byte[] message) {
        return Secp256k1.sign(message, (ECPrivateKey) keyPair.getPrivate());
    }

    public byte[] signTransaction(byte[] signingHash) {
        return sign(signingHash);
    }
}
