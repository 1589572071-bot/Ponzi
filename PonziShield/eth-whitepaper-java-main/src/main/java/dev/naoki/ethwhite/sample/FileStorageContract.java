package dev.naoki.ethwhite.sample;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.ExecutionException;
import dev.naoki.ethwhite.util.Hex;
import dev.naoki.ethwhite.util.Merkle;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public final class FileStorageContract extends AbstractNativeContract {
    @Override
    public void onDeploy(ContractContext context, CallData deploymentCall) {
        context.putMetadata("root", deploymentCall.arg("root"));
        context.putMetadata("leafCount", deploymentCall.arg("leafCount"));
        context.putMetadata("reward", deploymentCall.arg("reward"));
        context.putMetadata("interval", deploymentCall.argOrDefault("interval", "1"));
    }

    @Override
    public byte[] onMessage(ContractContext context, CallData callData) {
        return switch (callData.method()) {
            case "challenge" -> response(Integer.toString(currentChallenge(context)));
            case "prove" -> prove(context, callData);
            default -> throw new ExecutionException("Unsupported file storage method");
        };
    }

    private byte[] prove(ContractContext context, CallData callData) {
        int epoch = currentEpoch(context);
        require(context.metadata("claimed:" + epoch) == null, "Reward already claimed for current epoch");
        int index = Integer.parseInt(callData.arg("index"));
        require(index == currentChallenge(context), "Incorrect challenged index");
        byte[] root = Hex.decode(context.metadata("root"));
        byte[] leaf = Hex.decode(callData.arg("leaf"));
        List<byte[]> siblings = new ArrayList<>();
        for (String sibling : callData.list("siblings")) {
            if (!sibling.isBlank()) {
                siblings.add(Hex.decode(sibling));
            }
        }
        List<Integer> path = new ArrayList<>();
        for (String bit : callData.list("path")) {
            if (!bit.isBlank()) {
                path.add(Integer.parseInt(bit));
            }
        }
        require(Merkle.verify(leaf, siblings, path, root), "Invalid Merkle proof");
        BigInteger reward = metadataBig(context, "reward");
        require(context.state().getOrCreate(context.self()).balance().compareTo(reward) >= 0, "Insufficient contract reward balance");
        context.putMetadata("claimed:" + epoch, context.sender().toHex());
        context.state().transfer(context.self(), context.sender(), reward);
        return OK;
    }

    private int currentEpoch(ContractContext context) {
        long interval = Long.parseLong(context.metadata("interval"));
        return Math.toIntExact(context.block().number() / Math.max(interval, 1L));
    }

    private int currentChallenge(ContractContext context) {
        int leafCount = Integer.parseInt(context.metadata("leafCount"));
        require(leafCount > 0, "Leaf count must be positive");
        byte[] previous = context.block().previousBlockHash();
        int accumulator = 0;
        for (byte value : previous) {
            accumulator = ((accumulator << 5) - accumulator) + (value & 0xff);
        }
        accumulator += currentEpoch(context);
        return Math.floorMod(accumulator, leafCount);
    }
}
