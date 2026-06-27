package dev.naoki.ethwhite.sample;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.ExecutionException;
import dev.naoki.ethwhite.core.MessageResult;

import java.math.BigInteger;

public final class HedgeContract extends AbstractNativeContract {
    @Override
    public void onDeploy(ContractContext context, CallData deploymentCall) {
        putAddress(context, "partyA", address(deploymentCall, "partyA"));
        putAddress(context, "partyB", address(deploymentCall, "partyB"));
        putAddress(context, "priceFeed", address(deploymentCall, "priceFeed"));
        context.putMetadata("symbol", deploymentCall.arg("symbol"));
        context.putMetadata("expiry", deploymentCall.arg("expiry"));
        putFlag(context, "settled", false);
    }

    @Override
    public byte[] onMessage(ContractContext context, CallData callData) {
        return switch (callData.method()) {
            case "deposit" -> deposit(context);
            case "settle" -> settle(context);
            case "status" -> response(status(context));
            default -> throw new ExecutionException("Unsupported hedge method");
        };
    }

    private byte[] deposit(ContractContext context) {
        require(!metadataFlag(context, "settled"), "Contract already settled");
        require(context.value().signum() > 0, "Deposit value must be positive");
        Address partyA = metadataAddress(context, "partyA");
        Address partyB = metadataAddress(context, "partyB");
        require(context.sender().equals(partyA) || context.sender().equals(partyB), "Only hedge parties can deposit");
        require(context.metadata("startPrice") == null, "Deposits are closed");
        if (context.sender().equals(partyA)) {
            putBig(context, "depositA", metadataBig(context, "depositA").add(context.value()));
        } else {
            putBig(context, "depositB", metadataBig(context, "depositB").add(context.value()));
        }
        if (metadataBig(context, "depositA").signum() > 0 && metadataBig(context, "depositB").signum() > 0) {
            BigInteger price = currentPrice(context);
            putBig(context, "startPrice", price);
            putBig(context, "lockedA", metadataBig(context, "depositA"));
        }
        return OK;
    }

    private byte[] settle(ContractContext context) {
        require(!metadataFlag(context, "settled"), "Contract already settled");
        require(context.block().timestamp() >= Long.parseLong(context.metadata("expiry")), "Contract not yet expired");
        require(metadataBig(context, "depositA").signum() > 0 && metadataBig(context, "depositB").signum() > 0,
                "Both parties must deposit before settlement");
        BigInteger startPrice = metadataBig(context, "startPrice");
        require(startPrice.signum() > 0, "Missing initial hedge price");
        BigInteger currentPrice = currentPrice(context);
        BigInteger totalPot = context.state().getOrCreate(context.self()).balance();
        BigInteger lockedA = metadataBig(context, "lockedA");
        BigInteger payoutA = lockedA.multiply(startPrice).divide(currentPrice.max(BigInteger.ONE));
        if (payoutA.compareTo(totalPot) > 0) {
            payoutA = totalPot;
        }
        BigInteger payoutB = totalPot.subtract(payoutA);
        putFlag(context, "settled", true);
        context.state().transfer(context.self(), metadataAddress(context, "partyA"), payoutA);
        context.state().transfer(context.self(), metadataAddress(context, "partyB"), payoutB);
        return OK;
    }

    private BigInteger currentPrice(ContractContext context) {
        MessageResult result = context.call(
                metadataAddress(context, "priceFeed"),
                BigInteger.ZERO,
                CallData.builder("get").put("symbol", context.metadata("symbol")).build(),
                2_000
        );
        require(result.success(), "Unable to read price feed");
        return new BigInteger(new String(result.returnData()));
    }

    private String status(ContractContext context) {
        return "depositA=" + metadataBig(context, "depositA")
                + ",depositB=" + metadataBig(context, "depositB")
                + ",startPrice=" + metadataBig(context, "startPrice")
                + ",settled=" + metadataFlag(context, "settled");
    }
}
