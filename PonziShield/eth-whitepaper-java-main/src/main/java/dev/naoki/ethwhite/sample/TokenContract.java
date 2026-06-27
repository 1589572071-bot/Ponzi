package dev.naoki.ethwhite.sample;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.ExecutionException;

import java.math.BigInteger;

public final class TokenContract extends AbstractNativeContract {
    @Override
    public void onDeploy(ContractContext context, CallData deploymentCall) {
        String ownerHex = deploymentCall.argOrDefault("owner", context.sender().toHex());
        Address owner = ownerHex.equals(context.sender().toHex()) ? context.sender() : Address.fromHex(ownerHex);
        BigInteger supply = bigOrDefault(deploymentCall, "supply", BigInteger.ZERO);
        putAddress(context, "owner", owner);
        putBig(context, "totalSupply", supply);
        putBig(context, balanceKey(owner), supply);
    }

    @Override
    public byte[] onMessage(ContractContext context, CallData callData) {
        return switch (callData.method()) {
            case "transfer" -> transfer(context, callData);
            case "balanceOf" -> response(balance(context, address(callData, "owner")).toString());
            case "mint" -> mint(context, callData);
            case "totalSupply" -> response(metadataBig(context, "totalSupply").toString());
            default -> throw new ExecutionException("Unsupported token method");
        };
    }

    private byte[] transfer(ContractContext context, CallData callData) {
        Address to = address(callData, "to");
        BigInteger amount = big(callData, "amount");
        require(amount.signum() >= 0, "Amount must be non-negative");
        BigInteger senderBalance = balance(context, context.sender());
        require(senderBalance.compareTo(amount) >= 0, "Insufficient token balance");
        putBig(context, balanceKey(context.sender()), senderBalance.subtract(amount));
        putBig(context, balanceKey(to), balance(context, to).add(amount));
        return OK;
    }

    private byte[] mint(ContractContext context, CallData callData) {
        require(context.sender().equals(metadataAddress(context, "owner")), "Only owner can mint");
        Address to = address(callData, "to");
        BigInteger amount = big(callData, "amount");
        require(amount.signum() >= 0, "Amount must be non-negative");
        putBig(context, balanceKey(to), balance(context, to).add(amount));
        putBig(context, "totalSupply", metadataBig(context, "totalSupply").add(amount));
        return OK;
    }

    private static BigInteger balance(ContractContext context, Address owner) {
        return metadataBig(context, balanceKey(owner));
    }

    private static String balanceKey(Address owner) {
        return "balance:" + owner.toHex();
    }
}
