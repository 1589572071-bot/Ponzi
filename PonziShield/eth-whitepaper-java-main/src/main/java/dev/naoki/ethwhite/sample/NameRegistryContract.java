package dev.naoki.ethwhite.sample;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.ExecutionException;

public final class NameRegistryContract extends AbstractNativeContract {
    @Override
    public byte[] onMessage(ContractContext context, CallData callData) {
        return switch (callData.method()) {
            case "register" -> register(context, callData);
            case "resolve" -> response(context.metadata("value:" + callData.arg("name")));
            case "update" -> update(context, callData);
            case "transferOwnership" -> transferOwnership(context, callData);
            default -> throw new ExecutionException("Unsupported registry method");
        };
    }

    private byte[] register(ContractContext context, CallData callData) {
        String name = callData.arg("name");
        require(context.metadata("owner:" + name) == null, "Name already registered");
        context.putMetadata("owner:" + name, context.sender().toHex());
        context.putMetadata("value:" + name, callData.arg("value"));
        return OK;
    }

    private byte[] update(ContractContext context, CallData callData) {
        String name = callData.arg("name");
        require(context.sender().toHex().equals(context.metadata("owner:" + name)), "Only owner can update name");
        context.putMetadata("value:" + name, callData.arg("value"));
        return OK;
    }

    private byte[] transferOwnership(ContractContext context, CallData callData) {
        String name = callData.arg("name");
        require(context.sender().toHex().equals(context.metadata("owner:" + name)), "Only owner can transfer name");
        context.putMetadata("owner:" + name, callData.arg("to"));
        return OK;
    }
}
