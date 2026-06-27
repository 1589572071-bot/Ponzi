package dev.naoki.ethwhite.core;

import java.math.BigInteger;

public final class Units {
    public static final BigInteger WEI = BigInteger.ONE;
    public static final BigInteger SZABO = BigInteger.TEN.pow(12);
    public static final BigInteger FINNEY = BigInteger.TEN.pow(15);
    public static final BigInteger ETHER = BigInteger.TEN.pow(18);

    private Units() {
    }
}
