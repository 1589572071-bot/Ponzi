package dev.naoki.ethwhite.contract;

import dev.naoki.ethwhite.core.Address;

public final class ContractCreationResult {
    private final boolean success;
    private final Address address;
    private final long gasRemaining;
    private final String error;

    private ContractCreationResult(boolean success, Address address, long gasRemaining, String error) {
        this.success = success;
        this.address = address;
        this.gasRemaining = gasRemaining;
        this.error = error;
    }

    public static ContractCreationResult success(Address address, long gasRemaining) {
        return new ContractCreationResult(true, address, gasRemaining, null);
    }

    public static ContractCreationResult failure(long gasRemaining, String error) {
        return new ContractCreationResult(false, null, gasRemaining, error);
    }

    public boolean success() {
        return success;
    }

    public Address address() {
        return address;
    }

    public long gasRemaining() {
        return gasRemaining;
    }

    public String error() {
        return error;
    }
}
