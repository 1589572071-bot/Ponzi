package dev.naoki.ethwhite.contract;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class ContractRegistry {
    private final Map<String, NativeContract> nativeContracts = new HashMap<>();

    public ContractRegistry register(String id, NativeContract contract) {
        nativeContracts.put(id, Objects.requireNonNull(contract, "contract"));
        return this;
    }

    public NativeContract nativeContract(String id) {
        NativeContract contract = nativeContracts.get(id);
        if (contract == null) {
            throw new IllegalArgumentException("Unknown native contract: " + id);
        }
        return contract;
    }

    public boolean contains(String id) {
        return nativeContracts.containsKey(id);
    }
}
