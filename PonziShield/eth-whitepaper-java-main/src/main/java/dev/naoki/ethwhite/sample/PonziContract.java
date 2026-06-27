package dev.naoki.ethwhite.sample;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.ExecutionException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public final class PonziContract extends AbstractNativeContract {
    private static final BigInteger BPS = BigInteger.valueOf(10_000);

    @Override
    public void onDeploy(ContractContext context, CallData deploymentCall) {
        putBig(context, "minStake", bigOrDefault(deploymentCall, "minStake", BigInteger.valueOf(100)));
        putBig(context, "lockBlocks", bigOrDefault(deploymentCall, "lockBlocks", BigInteger.valueOf(2)));
        putBig(context, "referralBps", bigOrDefault(deploymentCall, "referralBps", BigInteger.valueOf(1_000)));
        putBig(context, "secondLevelBps", bigOrDefault(deploymentCall, "secondLevelBps", BigInteger.valueOf(500)));
        putBig(context, "investorPoolBps", bigOrDefault(deploymentCall, "investorPoolBps", BigInteger.valueOf(4_000)));
        context.putMetadata("investors", "");
    }

    @Override
    public byte[] onMessage(ContractContext context, CallData callData) {
        return switch (callData.method()) {
            case "stake" -> stake(context, callData);
            case "withdraw" -> withdraw(context);
            case "balanceOf" -> response(balance(context, address(callData, "owner")).toString());
            case "referrerOf" -> response(referrerOf(context, address(callData, "owner")).toHex());
            case "investorCount" -> response(Integer.toString(investors(context).size()));
            default -> throw new ExecutionException("Unsupported ponzi method");
        };
    }

    private byte[] stake(ContractContext context, CallData callData) {
        require(context.value().compareTo(metadataBig(context, "minStake")) >= 0, "Stake value below minimum");

        Address sender = context.sender();
        Address referrer = optionalAddress(callData, "referrer");
        if (!isInvestor(context, sender)) {
            addInvestor(context, sender);
            if (referrer != null && !referrer.equals(sender)) {
                putAddress(context, referrerKey(sender), referrer);
            }
        }
        putBig(context, lastStakeBlockKey(sender), BigInteger.valueOf(context.block().number()));

        BigInteger remaining = context.value();
        remaining = payReferralRewards(context, sender, remaining);
        remaining = payExistingInvestors(context, sender, remaining);

        // Leave part of each stake behind so early users can withdraw later.
        BigInteger withdrawCredit = remaining.divide(BigInteger.valueOf(3));
        putBig(context, balanceKey(sender), balance(context, sender).add(withdrawCredit));
        return OK;
    }

    private byte[] withdraw(ContractContext context) {
        Address sender = context.sender();
        BigInteger available = balance(context, sender);
        require(available.signum() > 0, "No withdrawable balance");

        BigInteger lockBlocks = metadataBig(context, "lockBlocks");
        BigInteger lastStakeBlock = metadataBig(context, lastStakeBlockKey(sender));
        BigInteger currentBlock = BigInteger.valueOf(context.block().number());
        require(currentBlock.subtract(lastStakeBlock).compareTo(lockBlocks) >= 0, "Withdraw is still locked");

        BigInteger contractBalance = context.state().getOrCreate(context.self()).balance();
        BigInteger payout = available.min(contractBalance);
        require(payout.signum() > 0, "Contract balance is empty");
        putBig(context, balanceKey(sender), available.subtract(payout));
        context.state().transfer(context.self(), sender, payout);
        return OK;
    }

    private BigInteger payReferralRewards(ContractContext context, Address sender, BigInteger remaining) {
        Address referrer = referrerOf(context, sender);
        if (referrer == null) {
            return remaining;
        }

        BigInteger primary = context.value().multiply(metadataBig(context, "referralBps")).divide(BPS);
        remaining = transferIfPossible(context, referrer, primary, remaining);

        Address secondLevel = referrerOf(context, referrer);
        if (secondLevel != null && !secondLevel.equals(sender)) {
            BigInteger secondary = context.value().multiply(metadataBig(context, "secondLevelBps")).divide(BPS);
            remaining = transferIfPossible(context, secondLevel, secondary, remaining);
        }
        return remaining;
    }

    private BigInteger payExistingInvestors(ContractContext context, Address sender, BigInteger remaining) {
        List<Address> previousInvestors = investors(context).stream()
                .filter(investor -> !investor.equals(sender))
                .toList();
        if (previousInvestors.isEmpty()) {
            return remaining;
        }

        BigInteger pool = context.value().multiply(metadataBig(context, "investorPoolBps")).divide(BPS);
        BigInteger share = pool.divide(BigInteger.valueOf(previousInvestors.size()));
        for (Address investor : previousInvestors) {
            remaining = transferIfPossible(context, investor, share, remaining);
        }
        return remaining;
    }

    private BigInteger transferIfPossible(ContractContext context, Address to, BigInteger amount, BigInteger remaining) {
        if (amount.signum() <= 0 || remaining.signum() <= 0) {
            return remaining;
        }
        BigInteger payout = amount.min(remaining);
        context.state().transfer(context.self(), to, payout);
        return remaining.subtract(payout);
    }

    private static Address optionalAddress(CallData callData, String key) {
        String raw = callData.argOrDefault(key, "");
        return raw.isBlank() ? null : Address.fromHex(raw);
    }

    private static boolean isInvestor(ContractContext context, Address address) {
        return context.metadata(investorKey(address)) != null;
    }

    private static void addInvestor(ContractContext context, Address address) {
        context.putMetadata(investorKey(address), "true");
        List<Address> investors = investors(context);
        investors.add(address);
        context.putMetadata("investors", encodeInvestors(investors));
    }

    private static List<Address> investors(ContractContext context) {
        String raw = context.metadata("investors");
        List<Address> investors = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return investors;
        }
        for (String value : raw.split(",")) {
            if (!value.isBlank()) {
                investors.add(Address.fromHex(value));
            }
        }
        return investors;
    }

    private static String encodeInvestors(List<Address> investors) {
        List<String> encoded = new ArrayList<>();
        for (Address investor : investors) {
            encoded.add(investor.toHex());
        }
        return String.join(",", encoded);
    }

    private static BigInteger balance(ContractContext context, Address owner) {
        return metadataBig(context, balanceKey(owner));
    }

    private static Address referrerOf(ContractContext context, Address owner) {
        return metadataAddress(context, referrerKey(owner));
    }

    private static String balanceKey(Address owner) {
        return "balance:" + owner.toHex();
    }

    private static String referrerKey(Address owner) {
        return "referrer:" + owner.toHex();
    }

    private static String investorKey(Address owner) {
        return "investor:" + owner.toHex();
    }

    private static String lastStakeBlockKey(Address owner) {
        return "lastStakeBlock:" + owner.toHex();
    }
}
