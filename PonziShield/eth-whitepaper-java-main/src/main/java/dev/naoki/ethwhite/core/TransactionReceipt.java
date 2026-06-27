package dev.naoki.ethwhite.core;

import java.math.BigInteger;
import java.util.Arrays;

public final class TransactionReceipt {
    private final boolean success;
    private final Address contractAddress;
    private final long gasUsed;
    private final BigInteger feePaid;
    private final byte[] returnData;
    private final String error;

    public TransactionReceipt(boolean success, Address contractAddress, long gasUsed, BigInteger feePaid, byte[] returnData, String error) {
        this.success = success;
        this.contractAddress = contractAddress;
        this.gasUsed = gasUsed;
        this.feePaid = feePaid;
        this.returnData = Arrays.copyOf(returnData, returnData.length);
        this.error = error;
    }

    public boolean success() {
        return success;
    }

    public Address contractAddress() {
        return contractAddress;
    }

    public long gasUsed() {
        return gasUsed;
    }

    public BigInteger feePaid() {
        return feePaid;
    }

    public byte[] returnData() {
        return Arrays.copyOf(returnData, returnData.length);
    }

    public String error() {
        return error;
    }
}
