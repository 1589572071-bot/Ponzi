package dev.naoki.ethwhite.crypto;

import dev.naoki.ethwhite.core.Address;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

public final class Secp256k1 {
    private static final String PROVIDER = "BC";
    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");

    static {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private Secp256k1() {
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", PROVIDER);
            generator.initialize(new ECGenParameterSpec("secp256k1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to generate secp256k1 key pair", exception);
        }
    }

    public static byte[] sign(byte[] message, ECPrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance("NONEwithECDSA", PROVIDER);
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign message", exception);
        }
    }

    public static boolean verify(byte[] message, byte[] signatureBytes, byte[] publicKeyBytes) {
        try {
            Signature signature = Signature.getInstance("NONEwithECDSA", PROVIDER);
            signature.initVerify(KeyFactory.getInstance("EC", PROVIDER).generatePublic(new X509EncodedKeySpec(publicKeyBytes)));
            signature.update(message);
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    public static Address address(byte[] publicKeyBytes) {
        return Address.fromPublicKey(uncompressedPoint(publicKeyBytes));
    }

    public static byte[] uncompressedPoint(byte[] publicKeyBytes) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC", PROVIDER);
            var publicKey = (java.security.interfaces.ECPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            var point = CURVE.getCurve().createPoint(publicKey.getW().getAffineX(), publicKey.getW().getAffineY());
            byte[] encoded = point.getEncoded(false);
            return Arrays.copyOfRange(encoded, 1, encoded.length);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to decode public key", exception);
        }
    }
}
