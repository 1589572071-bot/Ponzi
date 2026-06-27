package dev.naoki.ethwhite.sample;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.ExecutionException;

public final class PriceFeedContract extends AbstractNativeContract {
    @Override
    public void onDeploy(ContractContext context, CallData deploymentCall) {
        putAddress(context, "owner", context.sender());
        if (!deploymentCall.argOrDefault("symbol", "").isBlank()) {
            context.putMetadata("price:" + deploymentCall.arg("symbol"), deploymentCall.arg("price"));
        }
    }

    @Override
    public byte[] onMessage(ContractContext context, CallData callData) {
        return switch (callData.method()) {
            case "update" -> update(context, callData);
            case "get" -> response(context.metadata("price:" + callData.arg("symbol")));
            default -> throw new ExecutionException("Unsupported price feed method");
        };
    }

    private byte[] update(ContractContext context, CallData callData) {
        require(context.sender().equals(metadataAddress(context, "owner")), "Only owner can update feed");
        context.putMetadata("price:" + callData.arg("symbol"), callData.arg("price"));
        return OK;
    }
}
