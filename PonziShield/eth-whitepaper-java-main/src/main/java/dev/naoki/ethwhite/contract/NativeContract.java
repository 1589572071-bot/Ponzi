package dev.naoki.ethwhite.contract;

import dev.naoki.ethwhite.core.CallData;

public interface NativeContract {
    default void onDeploy(ContractContext context, CallData deploymentCall) {
    }

    byte[] onMessage(ContractContext context, CallData callData);
}
