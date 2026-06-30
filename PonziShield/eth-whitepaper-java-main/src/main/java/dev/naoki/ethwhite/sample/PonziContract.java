package dev.naoki.ethwhite.sample;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.ExecutionException;
import dev.naoki.ethwhite.ponzi.EventType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * PonziContract – a native Java implementation of a typical Ethereum
 * "Ponzi-style" smart contract, extended with:
 *
 * <h3>Innovation 1 – Dynamic Rate Mechanism</h3>
 * The referral and dividend rates are no longer fixed at deployment.
 * Once the number of investors reaches {@code phaseThreshold},
 * the contract enters the MATURE phase and the rates are automatically
 * reduced to model the "early high-yield temptation" pattern that
 * real-world Ponzi schemes exhibit.
 *
 * <h3>Innovation 2 – Typed Fund-Flow Events</h3>
 * Every outgoing transfer is now annotated with an {@link EventType},
 * so that the Python backend can distinguish stakes, withdrawals,
 * referral rewards, and dividends without heuristics.
 */
public final class PonziContract extends AbstractNativeContract {
    private static final BigInteger BPS = BigInteger.valueOf(10_000);

    // ── keys for dynamic-rate metadata ──────────────────────────────
    private static final String KEY_PHASE         = "dynamicPhase";
    private static final String KEY_EARLY_REF   = "earlyReferralBps";
    private static final String KEY_EARLY_POOL  = "earlyPoolBps";
    private static final String KEY_MATURE_REF   = "matureReferralBps";
    private static final String KEY_MATURE_POOL  = "maturePoolBps";
    private static final String KEY_THRESHOLD    = "phaseThreshold";

    @Override
    public void onDeploy(ContractContext context, CallData deploymentCall) {
        putBig(context, "minStake",         bigOrDefault(deploymentCall, "minStake",         BigInteger.valueOf(100)));
        putBig(context, "lockBlocks",        bigOrDefault(deploymentCall, "lockBlocks",        BigInteger.valueOf(2)));
        // ── early-stage (default) rates ──
        putBig(context, "referralBps",       bigOrDefault(deploymentCall, "referralBps",       BigInteger.valueOf(1_000)));
        putBig(context, "secondLevelBps",    bigOrDefault(deploymentCall, "secondLevelBps",    BigInteger.valueOf(500)));
        putBig(context, "investorPoolBps",   bigOrDefault(deploymentCall, "investorPoolBps",   BigInteger.valueOf(4_000)));
        // ── mature-stage rates (can be overridden at deploy) ──
        putBig(context, KEY_EARLY_REF,        bigOrDefault(deploymentCall, "earlyReferralBps",
                metadataBig(context, "referralBps")));
        putBig(context, KEY_EARLY_POOL,       bigOrDefault(deploymentCall, "earlyPoolBps",
                metadataBig(context, "investorPoolBps")));
        putBig(context, KEY_MATURE_REF,        bigOrDefault(deploymentCall, "matureReferralBps",
                metadataBig(context, "referralBps").multiply(BigInteger.valueOf(500)).divide(BigInteger.valueOf(1_000)))); // 50 %
        putBig(context, KEY_MATURE_POOL,      bigOrDefault(deploymentCall, "maturePoolBps",
                metadataBig(context, "investorPoolBps").multiply(BigInteger.valueOf(750)).divide(BigInteger.valueOf(1_000)))); // 75 %
        putBig(context, KEY_THRESHOLD,          bigOrDefault(deploymentCall, "phaseThreshold",    BigInteger.valueOf(5)));
        putString(context, KEY_PHASE, "EARLY");

        context.putMetadata("investors", "");
    }

    @Override
    public byte[] onMessage(ContractContext context, CallData callData) {
        return switch (callData.method()) {
            case "stake"            -> stake(context, callData);
            case "withdraw"         -> withdraw(context);
            case "balanceOf"        -> response(balance(context, address(callData, "owner")).toString());
            case "referrerOf"       -> response(referrerOf(context, address(callData, "owner")).toHex());
            case "investorCount"    -> response(Integer.toString(investors(context).size()));
            // ── new read-only helpers for dynamic rates ──
            case "phase"            -> response(phase(context));
            case "referralBpsNow"  -> response(currentReferralBps(context).toString());
            case "poolBpsNow"      -> response(currentPoolBps(context).toString());
            default -> throw new ExecutionException("Unsupported ponzi method");
        };
    }

    // ── Dynamic-rate helpers ────────────────────────────────────────

    /** Returns the current phase label ("EARLY" or "MATURE"). */
    private static String phase(ContractContext context) {
        return context.metadata(KEY_PHASE);
    }

    /** Referral BPS adjusted for the current phase. */
    private static BigInteger currentReferralBps(ContractContext context) {
        return "MATURE".equals(phase(context))
                ? metadataBig(context, KEY_MATURE_REF)
                : metadataBig(context, "referralBps");
    }

    /** Second-level referral BPS adjusted for the current phase. */
    private static BigInteger currentSecondLevelBps(ContractContext context) {
        return "MATURE".equals(phase(context))
                ? metadataBig(context, KEY_MATURE_REF).multiply(BigInteger.valueOf(500)).divide(BigInteger.valueOf(1_000))
                : metadataBig(context, "secondLevelBps");
    }

    /** Investor-pool BPS adjusted for the current phase. */
    private static BigInteger currentPoolBps(ContractContext context) {
        return "MATURE".equals(phase(context))
                ? metadataBig(context, KEY_MATURE_POOL)
                : metadataBig(context, "investorPoolBps");
    }

    /** Check & apply the phase transition after a new investor joins. */
    private static void maybeTransitionPhase(ContractContext context) {
        if ("EARLY".equals(phase(context))
                && BigInteger.valueOf(investors(context).size()).compareTo(metadataBig(context, KEY_THRESHOLD)) >= 0) {
            context.putMetadata(KEY_PHASE, "MATURE");
        }
    }

    // ── Core business logic ─────────────────────────────────────────

    private byte[] stake(ContractContext context, CallData callData) {
        require(context.value().compareTo(metadataBig(context, "minStake")) >= 0,
                "Stake value below minimum");

        Address sender = context.sender();
        Address referrer = optionalAddress(callData, "referrer");
        boolean isNew = !isInvestor(context, sender);

        if (isNew) {
            addInvestor(context, sender);
            maybeTransitionPhase(context);          // ← innovation 1 trigger
            if (referrer != null && !referrer.equals(sender)) {
                putAddress(context, referrerKey(sender), referrer);
            }
        }
        putBig(context, lastStakeBlockKey(sender),
                BigInteger.valueOf(context.block().number()));

        BigInteger remaining = context.value();

        // ── pay referral rewards (typed transfer) ──
        remaining = payReferralRewards(context, sender, remaining);

        // ── pay existing investors (typed transfer) ──
        remaining = payExistingInvestors(context, sender, remaining);

        // ── credit withdraw-able balance ──
        BigInteger withdrawCredit = remaining.divide(BigInteger.valueOf(3));
        putBig(context, balanceKey(sender),
                balance(context, sender).add(withdrawCredit));
        return OK;
    }

    private byte[] withdraw(ContractContext context) {
        Address sender = context.sender();
        BigInteger available = balance(context, sender);
        require(available.signum() > 0, "No withdrawable balance");

        BigInteger lockBlocks = metadataBig(context, "lockBlocks");
        BigInteger lastStakeBlock = metadataBig(context, lastStakeBlockKey(sender));
        BigInteger currentBlock = BigInteger.valueOf(context.block().number());
        require(currentBlock.subtract(lastStakeBlock).compareTo(lockBlocks) >= 0,
                "Withdraw is still locked");

        BigInteger contractBalance = context.state().getOrCreate(context.self()).balance();
        BigInteger payout = available.min(contractBalance);
        require(payout.signum() > 0, "Contract balance is empty");

        // ── typed transfer: WITHDRAW ──
        context.state().transfer(context.self(), sender, payout, EventType.WITHDRAW);

        putBig(context, balanceKey(sender), available.subtract(payout));
        return OK;
    }

    private BigInteger payReferralRewards(ContractContext context, Address sender, BigInteger remaining) {
        Address referrer = referrerOf(context, sender);
        if (referrer == null) {
            return remaining;
        }
        BigInteger primary = context.value().multiply(currentReferralBps(context)).divide(BPS);
        remaining = transferIfPossible(context, referrer, primary, remaining, EventType.REFERRAL_REWARD);

        Address secondLevel = referrerOf(context, referrer);
        if (secondLevel != null && !secondLevel.equals(sender)) {
            BigInteger secondary = context.value().multiply(currentSecondLevelBps(context)).divide(BPS);
            remaining = transferIfPossible(context, secondLevel, secondary, remaining, EventType.REFERRAL_REWARD);
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
        BigInteger pool = context.value().multiply(currentPoolBps(context)).divide(BPS);
        BigInteger share = pool.divide(BigInteger.valueOf(previousInvestors.size()));
        for (Address investor : previousInvestors) {
            remaining = transferIfPossible(context, investor, share, remaining, EventType.DIVIDEND);
        }
        return remaining;
    }

    // ── overload: transfer with EventType ─────────────────────────
    private BigInteger transferIfPossible(ContractContext context, Address to,
                                         BigInteger amount, BigInteger remaining,
                                         EventType eventType) {
        if (amount.signum() <= 0 || remaining.signum() <= 0) {
            return remaining;
        }
        BigInteger payout = amount.min(remaining);
        context.state().transfer(context.self(), to, payout, eventType);
        return remaining.subtract(payout);
    }

    // ── original overload (kept for backward compatibility) ───────
    private BigInteger transferIfPossible(ContractContext context, Address to,
                                         BigInteger amount, BigInteger remaining) {
        return transferIfPossible(context, to, amount, remaining, EventType.TRANSFER);
    }

    // ── helpers (unchanged except for formatting) ──────────────────

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
