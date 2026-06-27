package dev.naoki.ethwhite.contract;

import dev.naoki.ethwhite.core.CallData;

public final class ContractDeployment {
    private ContractDeployment() {
    }

    public static byte[] nativeContract(String id) {
        return CallData.builder("native").put("id", id).build().encode();
    }

    public static byte[] nativeContract(CallData deployment) {
        return deployment.encode();
    }
}
