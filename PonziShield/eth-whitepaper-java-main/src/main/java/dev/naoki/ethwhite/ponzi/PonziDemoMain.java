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

public final class PonziDemoMain {
    private static final long GAS_LIMIT = 120_000;
    private static final BigInteger GAS_PRICE = BigInteger.ONE;

    private PonziDemoMain() {
    }

    public static void main(String[] args) {
        boolean analysisEnabled = Boolean.parseBoolean(System.getProperty("analysis.enabled", "true"));
        FundFlowEmitter emitter = analysisEnabled ? FundFlowEmitter.fromSystemProperties() : null;
        DemoListener listener = new DemoListener(emitter);

        try {
            runDemo(listener);
        } finally {
            if (emitter != null) {
                emitter.close();
            }
        }
    }

    private static void runDemo(FundFlowListener listener) {
        TransactionProcessor processor = new TransactionProcessor(ContractCatalog.standardRegistry(), listener);
        WorldState state = new WorldState();
        Address miner = Address.random();
        Wallet owner = Wallet.create();
        List<Wallet> investors = createInvestors(10);

        state.getOrCreate(owner.address()).credit(Units.ETHER.multiply(BigInteger.TWO));
        for (Wallet investor : investors) {
            state.getOrCreate(investor.address()).credit(Units.ETHER.multiply(BigInteger.TWO));
        }

        TransactionReceipt deployReceipt = applyOrThrow(
                processor,
                state,
                SignedTransaction.sign(Transaction.createContract(
                        0,
                        BigInteger.ZERO,
                        CallData.builder("native")
                                .put("id", "ponzi")
                                .put("minStake", Units.FINNEY.multiply(BigInteger.TEN))
                                .put("lockBlocks", 2)
                                .build()
                                .encode(),
                        GAS_LIMIT,
                        GAS_PRICE
                ), owner),
                block(1, miner)
        );
        Address ponzi = deployReceipt.contractAddress();
        System.out.println("Ponzi contract: " + ponzi);

        long blockNumber = 2;
        for (int i = 0; i < investors.size(); i++) {
            Wallet investor = investors.get(i);
            Address referrer = i == 0 ? owner.address() : investors.get(i - 1).address();
            BigInteger stakeValue = Units.FINNEY.multiply(BigInteger.valueOf(20L + i * 2L));
            applyOrThrow(
                    processor,
                    state,
                    SignedTransaction.sign(new Transaction(
                            0,
                            ponzi,
                            stakeValue,
                            CallData.builder("stake").put("referrer", referrer).build().encode(),
                            GAS_LIMIT,
                            GAS_PRICE
                    ), investor),
                    block(blockNumber++, miner)
            );
        }

        for (int i = 0; i < 3; i++) {
            Wallet investor = investors.get(i);
            applyOrThrow(
                    processor,
                    state,
                    SignedTransaction.sign(new Transaction(
                            1,
                            ponzi,
                            BigInteger.ZERO,
                            CallData.builder("withdraw").build().encode(),
                            GAS_LIMIT,
                            GAS_PRICE
                    ), investor),
                    block(blockNumber++, miner)
            );
        }

        System.out.println("Demo complete: 10 stake transactions + 3 withdraw transactions");
        System.out.println("Contract balance: " + state.describeBalance(ponzi));
        System.out.println("Miner fees collected: " + state.describeBalance(miner));
    }

    private static List<Wallet> createInvestors(int count) {
        List<Wallet> investors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            investors.add(Wallet.create());
        }
        return investors;
    }

    private static BlockContext block(long number, Address miner) {
        return new BlockContext(number, 1_700_000_000L + number * 12, new byte[32], miner, 1_000_000L, 1L);
    }

    private static TransactionReceipt applyOrThrow(
            TransactionProcessor processor,
            WorldState state,
            SignedTransaction transaction,
            BlockContext block
    ) {
        TransactionReceipt receipt = processor.apply(state, transaction, block);
        if (!receipt.success()) {
            throw new IllegalStateException("Transaction failed: " + receipt.error());
        }
        return receipt;
    }

    private static final class DemoListener implements FundFlowListener {
        private final FundFlowListener delegate;

        private DemoListener(FundFlowListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onTransfer(FundFlowEvent event) {
            System.out.printf(
                    "transfer block=%d from=%s to=%s value=%s%n",
                    event.blockNumber(),
                    shortAddress(event.from().toHex()),
                    shortAddress(event.to().toHex()),
                    event.value()
            );
            if (delegate != null) {
                delegate.onTransfer(event);
            }
        }

        private static String shortAddress(String address) {
            return address.substring(0, 8) + "..." + address.substring(address.length() - 4);
        }
    }
}
