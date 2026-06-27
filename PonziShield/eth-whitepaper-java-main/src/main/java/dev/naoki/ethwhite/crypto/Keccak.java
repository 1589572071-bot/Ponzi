package dev.naoki.ethwhite.crypto;

import org.bouncycastle.jcajce.provider.digest.Keccak.Digest256;

public final class Keccak {
    private Keccak() {
    }

    public static byte[] hash(byte[] input) {
        return new Digest256().digest(input);
    }
}
