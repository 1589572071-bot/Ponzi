package dev.naoki.ethwhite.core;

import java.util.Arrays;

public final class MessageResult {
    private final boolean success;
    private final long gasRemaining;
    private final byte[] returnData;
    private final String error;

    private MessageResult(boolean success, long gasRemaining, byte[] returnData, String error) {
        this.success = success;
        this.gasRemaining = gasRemaining;
        this.returnData = Arrays.copyOf(returnData, returnData.length);
        this.error = error;
    }

    public static MessageResult success(long gasRemaining, byte[] returnData) {
        return new MessageResult(true, gasRemaining, returnData, null);
    }

    public static MessageResult failure(long gasRemaining, String error) {
        return new MessageResult(false, gasRemaining, new byte[0], error);
    }

    public boolean success() {
        return success;
    }

    public long gasRemaining() {
        return gasRemaining;
    }

    public byte[] returnData() {
        return Arrays.copyOf(returnData, returnData.length);
    }

    public String error() {
        return error;
    }
}
