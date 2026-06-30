package dev.naoki.ethwhite.ponzi;

import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.BlockContext;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.SignedTransaction;
import dev.naoki.ethwhite.core.Transaction;
import dev.naoki.ethwhite.core.TransactionProcessor;
import dev.naoki.ethwhite.core.TransactionReceipt;
import dev.naoki.ethwhite.core.Units;
import dev.naoki.ethwhite.core.WorldState;
import dev.naoki.ethwhite.crypto.Wallet;
import dev.naoki.ethwhite.sample.ContractCatalog;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry-point for the PonziShield demo.
 *
 * <h3>Innovation 3 – Richer Demo Scenarios</h3>
 * In addition to the original linear-chain demo (10 stakes + 3 withdrawals),
 * two new scenarios are provided:
 *
 * <ul>
 *   <li>{@code network} – a mesh referral structure that produces a
 *       non-trivial transaction graph (better for visualisation).</li>
 *   <li>{@code whale} – a single large investor stakes and exits early,
 *       modelling the "insider exit" pattern that often precedes collapse.</li>
 * </ul>
 *
 * Select the scenario with the {@code -Ddemo.type=...} system property;
 * the original demo remains the default.
 */
public final class PonziDemoMain {
    private static final long GAS_LIMIT = 120_000;
    private static final BigInteger GAS_PRICE = BigInteger.ONE;

    private PonziDemoMain() {
    }

    public static void main(String[] args) {
        boolean analysisEnabled = Boolean.parseBoolean(
                System.getProperty("analysis.enabled", "true"));
        FundFlowEmitter emitter = analysisEnabled
                ? FundFlowEmitter.fromSystemProperties()
                : null;
        DemoListener listener = new DemoListener(emitter);

        String demoType = System.getProperty("demo.type", "default").toLowerCase();

        try {
            switch (demoType) {
                case "network" -> runNetworkDemo(listener);
                case "whale"   -> runWhaleEarlyExitDemo(listener);
                default        -> runDemo(listener);
            }
        } finally {
            if (emitter != null) {
                emitter.close();
            }
        }
    }

    // ── Original linear-chain demo (backward-compatible default) ──────

    private static void runDemo(FundFlowListener listener) {
        TransactionProcessor processor = new TransactionProcessor(
                ContractCatalog.standardRegistry(), listener);
        WorldState state = new WorldState();
        Address miner = Address.random();
        Wallet owner = Wallet.create();
        List<Wallet> investors = createInvestors(10);

        fundAccounts(state, owner, investors);

        TransactionReceipt deployReceipt = applyOrThrow(processor, state,
                deployTx(owner), block(1, miner));
        Address ponzi = deployReceipt.contractAddress();
        System.out.println("Ponzi contract: " + ponzi);

        long blockNumber = 2;
        for (int i = 0; i < investors.size(); i++) {
            Wallet investor = investors.get(i);
            Address referrer = i == 0
                    ? owner.address()
                    : investors.get(i - 1).address();
            BigInteger stakeValue = Units.FINNEY.multiply(
                    BigInteger.valueOf(20L + i * 2L));
            applyOrThrow(processor, state,
                    stakeTx(investor, ponzi, referrer, stakeValue),
                    block(blockNumber++, miner));
        }

        for (int i = 0; i < 3; i++) {
            Wallet investor = investors.get(i);
            applyOrThrow(processor, state,
                    withdrawTx(investor, ponzi),
                    block(blockNumber++, miner));
        }

        System.out.println("Demo complete: 10 stake transactions + 3 withdraw transactions");
        System.out.println("Contract balance: " + state.describeBalance(ponzi));
        System.out.println("Miner fees collected: " + state.describeBalance(miner));
    }

    // ── Innovation 3a: Network (mesh) demo ────────────────────────

    /**
     * Mesh referral structure:
     * <pre>
     *        Owner
     *       /  |  \
     *     Hub1 Hub2 Hub3
     *     / \   / \   / \
     *   L1 L2 L3 L4 L5 L6
     * </pre>
     * Each hub refers the next hub (circular), and each leaf refers its hub.
     * This produces a non-linear transaction graph with recognizable clusters.
     */
    private static void runNetworkDemo(FundFlowListener listener) {
        TransactionProcessor processor = new TransactionProcessor(
                ContractCatalog.standardRegistry(), listener);
        WorldState state = new WorldState();
        Address miner = Address.random();
        Wallet owner = Wallet.create();
        Wallet hub1  = Wallet.create();
        Wallet hub2  = Wallet.create();
        Wallet hub3  = Wallet.create();
        List<Wallet> leaves = createInvestors(6);

        state.getOrCreate(owner.address()).credit(Units.ETHER.multiply(BigInteger.TWO));
        for (Wallet w : List.of(hub1, hub2, hub3)) {
            state.getOrCreate(w.address()).credit(Units.ETHER.multiply(BigInteger.TWO));
        }
        for (Wallet w : leaves) {
            state.getOrCreate(w.address()).credit(Units.ETHER.multiply(BigInteger.TWO));
        }

        TransactionReceipt deployReceipt = applyOrThrow(processor, state,
                deployTx(owner), block(1, miner));
        Address ponzi = deployReceipt.contractAddress();
        System.out.println("[network] Ponzi contract: " + ponzi);

        long blockNumber = 2;

        // Owner stakes first (no referrer)
        applyOrThrow(processor, state,
                stakeTx(owner, ponzi, null, Units.FINNEY.multiply(BigInteger.valueOf(30))),
                block(blockNumber++, miner));

        // Three hubs stake, each referring the previous (circular)
        applyOrThrow(processor, state,
                stakeTx(hub1, ponzi, owner.address(), Units.FINNEY.multiply(BigInteger.valueOf(25))),
                block(blockNumber++, miner));
        applyOrThrow(processor, state,
                stakeTx(hub2, ponzi, hub1.address(), Units.FINNEY.multiply(BigInteger.valueOf(25))),
                block(blockNumber++, miner));
        applyOrThrow(processor, state,
                stakeTx(hub3, ponzi, hub2.address(), Units.FINNEY.multiply(BigInteger.valueOf(25))),
                block(blockNumber++, miner));

        // Leaves stake, referring their hub
        for (int i = 0; i < leaves.size(); i++) {
            Wallet hub = i < 2 ? hub1 : i < 4 ? hub2 : hub3;
            applyOrThrow(processor, state,
                    stakeTx(leaves.get(i), ponzi, hub.address(),
                            Units.FINNEY.multiply(BigInteger.valueOf(15 + i))),
                    block(blockNumber++, miner));
        }

        // A few withdrawals from hubs (models "payout" activity)
        for (Wallet hub : List.of(hub1, hub2)) {
            applyOrThrow(processor, state,
                    withdrawTx(hub, ponzi),
                    block(blockNumber++, miner));
        }

        System.out.println("[network] Demo complete: mesh referral graph generated");
        System.out.println("[network] Contract balance: " + state.describeBalance(ponzi));
    }

    // ── Innovation 3b: Whale (early-exit) demo ─────────────────────

    /**
     * Whale-exit pattern:
     * 1. Whale stakes a large amount (5 ETH)
     * 2. Several small investors stake (modelling "following the crowd")
     * 3. Whale withdraws early (lock period expired)
     * 4. More small investors stake (modelling victims who join too late)
     *
     * This produces a recognizable "early exit" signature in the transaction
     * graph: a large out-flow from the contract to a single address
     * shortly after a large in-flow.
     */
    private static void runWhaleEarlyExitDemo(FundFlowListener listener) {
        TransactionProcessor processor = new TransactionProcessor(
                ContractCatalog.standardRegistry(), listener);
        WorldState state = new WorldState();
        Address miner = Address.random();
        Wallet whale = Wallet.create();
        Wallet owner = Wallet.create();
        List<Wallet> small = createInvestors(6);

        whale.address(); // force creation
        state.getOrCreate(whale.address()).credit(Units.ETHER.multiply(BigInteger.valueOf(10)));
        state.getOrCreate(owner.address()).credit(Units.ETHER.multiply(BigInteger.TWO));
        for (Wallet w : small) {
            state.getOrCreate(w.address()).credit(Units.ETHER.multiply(BigInteger.TWO));
        }

        TransactionReceipt deployReceipt = applyOrThrow(processor, state,
                deployTx(owner), block(1, miner));
        Address ponzi = deployReceipt.contractAddress();
        System.out.println("[whale] Ponzi contract: " + ponzi);

        long blockNumber = 2;

        // 1. Whale stakes 5 ETH (large)
        applyOrThrow(processor, state,
                stakeTx(whale, ponzi, owner.address(),
                        Units.ETHER.multiply(BigInteger.valueOf(5))),
                block(blockNumber++, miner));
        System.out.println("[whale] Block " + (blockNumber - 1) + ": whale staked 5 ETH");

        // 2. Small investors follow (3 of them)
        for (int i = 0; i < 3; i++) {
            applyOrThrow(processor, state,
                    stakeTx(small.get(i), ponzi, whale.address(),
                            Units.FINNEY.multiply(BigInteger.valueOf(20 + i * 5))),
                    block(blockNumber++, miner));
        }

        // 3. Whale exits early (after lockBlocks=2 have passed)
        long whaleStakeBlock = 2;
        long withdrawBlock = whaleStakeBlock + 3; // past lockBlocks=2
        while (blockNumber < withdrawBlock) {
            blockNumber++;
        }
        applyOrThrow(processor, state,
                withdrawTx(whale, ponzi),
                block(withdrawBlock, miner));
        System.out.println("[whale] Block " + withdrawBlock + ": whale WITHDREW early");
        blockNumber = withdrawBlock + 1;

        // 4. More small investors join (too late)
        for (int i = 3; i < small.size(); i++) {
            applyOrThrow(processor, state,
                    stakeTx(small.get(i), ponzi, small.get(0).address(),
                            Units.FINNEY.multiply(BigInteger.valueOf(18))),
                    block(blockNumber++, miner));
        }

        System.out.println("[whale] Demo complete: whale early-exit pattern generated");
        System.out.println("[whale] Contract balance: " + state.describeBalance(ponzi));
    }

    // ── Helper methods ────────────────────────────────────────────────

    private static List<Wallet> createInvestors(int count) {
        List<Wallet> investors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            investors.add(Wallet.create());
        }
        return investors;
    }

    private static void fundAccounts(WorldState state, Wallet owner,
                                   List<Wallet> investors) {
        state.getOrCreate(owner.address()).credit(
                Units.ETHER.multiply(BigInteger.TWO));
        for (Wallet investor : investors) {
            state.getOrCreate(investor.address()).credit(
                    Units.ETHER.multiply(BigInteger.TWO));
        }
    }

    private static SignedTransaction deployTx(Wallet owner) {
        return SignedTransaction.sign(
                Transaction.createContract(0, BigInteger.ZERO,
                        CallData.builder("native")
                                .put("id", "ponzi")
                                .put("minStake", Units.FINNEY.multiply(BigInteger.TEN))
                                .put("lockBlocks", 2)
                                .build().encode(),
                        GAS_LIMIT, GAS_PRICE),
                owner);
    }

    private static SignedTransaction stakeTx(Wallet from, Address ponzi,
                                              Address referrer, BigInteger value) {
        CallData.Builder b = CallData.builder("stake");
        if (referrer != null) {
            b = b.put("referrer", referrer.toHex());
        }
        return SignedTransaction.sign(
                new Transaction(0, ponzi, value, b.build().encode(),
                        GAS_LIMIT, GAS_PRICE),
                from);
    }

    private static SignedTransaction withdrawTx(Wallet from, Address ponzi) {
        return SignedTransaction.sign(
                new Transaction(1, ponzi, BigInteger.ZERO,
                        CallData.builder("withdraw").build().encode(),
                        GAS_LIMIT, GAS_PRICE),
                from);
    }

    private static BlockContext block(long number, Address miner) {
        return new BlockContext(number,
                1_700_000_000L + number * 12,
                new byte[32], miner, 1_000_000L, 1L);
    }

    private static TransactionReceipt applyOrThrow(
            TransactionProcessor processor, WorldState state,
            SignedTransaction tx, BlockContext blk) {
        TransactionReceipt receipt = processor.apply(state, tx, blk);
        if (!receipt.success()) {
            throw new IllegalStateException(
                    "Transaction failed: " + receipt.error());
        }
        return receipt;
    }

    // ── Demo listener (unchanged) ──────────────────────────────────

    private static final class DemoListener implements FundFlowListener {
        private final FundFlowListener delegate;

        private DemoListener(FundFlowListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onTransfer(FundFlowEvent event) {
            System.out.printf(
                    "transfer block=%d from=%s to=%s value=%s type=%s%n",
                    event.blockNumber(),
                    shortAddress(event.from().toHex()),
                    shortAddress(event.to().toHex()),
                    event.value(),
                    event.eventType());
            if (delegate != null) {
                delegate.onTransfer(event);
            }
        }

        private static String shortAddress(String address) {
            return address.substring(0, 8) + "..."
                    + address.substring(address.length() - 4);
        }
    }
}
