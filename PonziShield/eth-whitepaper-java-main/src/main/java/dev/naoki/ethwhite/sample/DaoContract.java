package dev.naoki.ethwhite.sample;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.core.Account;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.ExecutionException;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class DaoContract extends AbstractNativeContract {
    @Override
    public void onDeploy(ContractContext context, CallData deploymentCall) {
        List<Address> members = addressList(deploymentCall, "members");
        require(!members.isEmpty(), "DAO requires at least one member");
        for (Address member : members) {
            context.putMetadata(memberKey(member), "true");
        }
        context.putMetadata("memberCount", Integer.toString(members.size()));
    }

    @Override
    public byte[] onMessage(ContractContext context, CallData callData) {
        return switch (callData.method()) {
            case "propose" -> propose(context, callData);
            case "vote" -> vote(context, callData);
            case "finalize" -> finalizeProposal(context, callData);
            case "votes" -> response(context.metadata("proposal:" + callData.arg("id") + ":votes"));
            default -> throw new ExecutionException("Unsupported DAO method");
        };
    }

    private byte[] propose(ContractContext context, CallData callData) {
        require(isMember(context, context.sender()), "Only members can propose");
        String id = callData.arg("id");
        require(context.metadata("proposal:" + id + ":target") == null, "Proposal already exists");
        context.putMetadata("proposal:" + id + ":target", callData.arg("target"));
        context.putMetadata("proposal:" + id + ":key", callData.arg("key"));
        context.putMetadata("proposal:" + id + ":value", callData.arg("value"));
        context.putMetadata("proposal:" + id + ":votes", "0");
        context.putMetadata("proposal:" + id + ":finalized", "false");
        return OK;
    }

    private byte[] vote(ContractContext context, CallData callData) {
        require(isMember(context, context.sender()), "Only members can vote");
        String id = callData.arg("id");
        require(context.metadata("proposal:" + id + ":target") != null, "Unknown proposal");
        require(!Boolean.parseBoolean(context.metadata("proposal:" + id + ":finalized")), "Proposal already finalized");
        String voteKey = "proposal:" + id + ":voted:" + context.sender().toHex();
        require(context.metadata(voteKey) == null, "Member already voted");
        context.putMetadata(voteKey, "true");
        int votes = Integer.parseInt(context.metadata("proposal:" + id + ":votes"));
        context.putMetadata("proposal:" + id + ":votes", Integer.toString(votes + 1));
        return OK;
    }

    private byte[] finalizeProposal(ContractContext context, CallData callData) {
        String id = callData.arg("id");
        require(context.metadata("proposal:" + id + ":target") != null, "Unknown proposal");
        require(!Boolean.parseBoolean(context.metadata("proposal:" + id + ":finalized")), "Proposal already finalized");
        int votes = Integer.parseInt(context.metadata("proposal:" + id + ":votes"));
        int memberCount = Integer.parseInt(context.metadata("memberCount"));
        require(votes * 3 >= memberCount * 2, "Proposal has not reached two-thirds support");
        context.putMetadata("proposal:" + id + ":finalized", "true");
        applyMutation(context,
                Address.fromHex(context.metadata("proposal:" + id + ":target")),
                context.metadata("proposal:" + id + ":key"),
                context.metadata("proposal:" + id + ":value"));
        return OK;
    }

    private void applyMutation(ContractContext context, Address target, String key, String value) {
        Account account = context.state().getOrCreate(target);
        if (target.equals(context.self()) && key.startsWith("member:")) {
            Address member = Address.fromHex(key.substring("member:".length()));
            boolean adding = Boolean.parseBoolean(value);
            boolean alreadyMember = isMember(context, member);
            if (adding && !alreadyMember) {
                account.metadata().put(memberKey(member), "true".getBytes(StandardCharsets.UTF_8));
                account.metadata().put("memberCount", Integer.toString(Integer.parseInt(context.metadata("memberCount")) + 1).getBytes(StandardCharsets.UTF_8));
            } else if (!adding && alreadyMember) {
                account.metadata().remove(memberKey(member));
                account.metadata().put("memberCount", Integer.toString(Integer.parseInt(context.metadata("memberCount")) - 1).getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        account.metadata().put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isMember(ContractContext context, Address address) {
        byte[] value = context.state().getOrCreate(context.self()).metadata().get(memberKey(address));
        return value != null && "true".equals(new String(value, StandardCharsets.UTF_8));
    }

    private static String memberKey(Address address) {
        return "member:" + address.toHex();
    }
}
