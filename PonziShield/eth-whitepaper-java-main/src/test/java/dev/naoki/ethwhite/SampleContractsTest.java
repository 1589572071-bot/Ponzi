package dev.naoki.ethwhite;

import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.BlockContext;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.Transaction;
import dev.naoki.ethwhite.core.TransactionProcessor;
import dev.naoki.ethwhite.core.TransactionReceipt;
import dev.naoki.ethwhite.core.Units;
import dev.naoki.ethwhite.core.WorldState;
import dev.naoki.ethwhite.crypto.Wallet;
import dev.naoki.ethwhite.sample.ContractCatalog;
import dev.naoki.ethwhite.util.Hex;
import dev.naoki.ethwhite.util.Merkle;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SampleContractsTest {
    @Test
    void tokenContractTransfersBalances() {
        TransactionProcessor processor = new TransactionProcessor(ContractCatalog.standardRegistry());
        WorldState state = new WorldState();
        Wallet owner = Wallet.create();
        Wallet bob = Wallet.create();
        Address miner = Address.random();
        state.getOrCreate(owner.address()).credit(Units.ETHER.multiply(BigInteger.TEN));
        state.getOrCreate(bob.address()).credit(Units.ETHER);

        Transaction deploy = Transaction.createContract(
                0,
                BigInteger.ZERO,
                CallData.builder("native").put("id", "token").put("owner", owner.address()).put("supply", 1_000).build().encode(),
                10_000,
                BigInteger.ONE
        );
        TransactionReceipt deployed = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(deploy, owner),
                new BlockContext(1, 1_700_000_000L, new byte[32], miner, 1_000_000L, 1L));

        Transaction transfer = new Transaction(
                1,
                deployed.contractAddress(),
                BigInteger.ZERO,
                CallData.builder("transfer").put("to", bob.address()).put("amount", 125).build().encode(),
                10_000,
                BigInteger.ONE
        );
        processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(transfer, owner),
                new BlockContext(2, 1_700_000_010L, new byte[32], miner, 1_000_000L, 1L));

        Transaction balanceOf = new Transaction(
                0,
                deployed.contractAddress(),
                BigInteger.ZERO,
                CallData.builder("balanceOf").put("owner", bob.address()).build().encode(),
                10_000,
                BigInteger.ONE
        );
        TransactionReceipt balanceReceipt = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(balanceOf, bob),
                new BlockContext(3, 1_700_000_020L, new byte[32], miner, 1_000_000L, 1L));

        assertEquals("125", new String(balanceReceipt.returnData(), StandardCharsets.UTF_8));
    }

    @Test
    void hedgeContractSettlesAgainstPriceFeed() {
        TransactionProcessor processor = new TransactionProcessor(ContractCatalog.standardRegistry());
        WorldState state = new WorldState();
        Wallet feedOwner = Wallet.create();
        Wallet a = Wallet.create();
        Wallet b = Wallet.create();
        Address miner = Address.random();
        state.getOrCreate(feedOwner.address()).credit(Units.ETHER.multiply(BigInteger.TEN));
        state.getOrCreate(a.address()).credit(Units.ETHER.multiply(BigInteger.TEN));
        state.getOrCreate(b.address()).credit(Units.ETHER.multiply(BigInteger.TEN));

        Transaction feedDeploy = Transaction.createContract(
                0, BigInteger.ZERO,
                CallData.builder("native").put("id", "priceFeed").put("symbol", "ETHUSD").put("price", 2_000).build().encode(),
                10_000, BigInteger.ONE
        );
        TransactionReceipt feedReceipt = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(feedDeploy, feedOwner),
                new BlockContext(1, 1_700_000_000L, new byte[32], miner, 1_000_000L, 1L));

        Transaction hedgeDeploy = Transaction.createContract(
                0, BigInteger.ZERO,
                CallData.builder("native").put("id", "hedge")
                        .put("partyA", a.address())
                        .put("partyB", b.address())
                        .put("priceFeed", feedReceipt.contractAddress())
                        .put("symbol", "ETHUSD")
                        .put("expiry", 1_700_000_100L)
                        .build().encode(),
                20_000, BigInteger.ONE
        );
        TransactionReceipt hedgeReceipt = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(hedgeDeploy, a),
                new BlockContext(2, 1_700_000_010L, new byte[32], miner, 1_000_000L, 1L));

        processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(1, hedgeReceipt.contractAddress(), BigInteger.valueOf(1_000),
                                CallData.builder("deposit").build().encode(), 20_000, BigInteger.ONE), a),
                new BlockContext(3, 1_700_000_020L, new byte[32], miner, 1_000_000L, 1L));

        processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(0, hedgeReceipt.contractAddress(), BigInteger.valueOf(1_000),
                                CallData.builder("deposit").build().encode(), 20_000, BigInteger.ONE), b),
                new BlockContext(4, 1_700_000_030L, new byte[32], miner, 1_000_000L, 1L));

        processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(1, feedReceipt.contractAddress(), BigInteger.ZERO,
                                CallData.builder("update").put("symbol", "ETHUSD").put("price", 1_000).build().encode(),
                                10_000, BigInteger.ONE), feedOwner),
                new BlockContext(5, 1_700_000_040L, new byte[32], miner, 1_000_000L, 1L));

        processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(2, hedgeReceipt.contractAddress(), BigInteger.ZERO,
                                CallData.builder("settle").build().encode(), 20_000, BigInteger.ONE), a),
                new BlockContext(6, 1_700_000_200L, new byte[32], miner, 1_000_000L, 1L));

        assertTrue(state.getOrCreate(a.address()).balance().compareTo(state.getOrCreate(b.address()).balance()) > 0);
        assertEquals(BigInteger.ZERO, state.getOrCreate(hedgeReceipt.contractAddress()).balance());
    }

    @Test
    void daoCanAddMembersAfterTwoThirdsVote() {
        TransactionProcessor processor = new TransactionProcessor(ContractCatalog.standardRegistry());
        WorldState state = new WorldState();
        Wallet a = Wallet.create();
        Wallet b = Wallet.create();
        Wallet c = Wallet.create();
        Wallet d = Wallet.create();
        Address miner = Address.random();
        state.getOrCreate(a.address()).credit(Units.ETHER.multiply(BigInteger.TEN));
        state.getOrCreate(b.address()).credit(Units.ETHER.multiply(BigInteger.TEN));
        state.getOrCreate(c.address()).credit(Units.ETHER.multiply(BigInteger.TEN));
        state.getOrCreate(d.address()).credit(Units.ETHER);

        Transaction deploy = Transaction.createContract(
                0, BigInteger.ZERO,
                CallData.builder("native").put("id", "dao")
                        .putList("members", List.of(a.address().toHex(), b.address().toHex(), c.address().toHex()))
                        .build().encode(),
                20_000, BigInteger.ONE
        );
        TransactionReceipt dao = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(deploy, a),
                new BlockContext(1, 1_700_000_000L, new byte[32], miner, 1_000_000L, 1L));

        processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(1, dao.contractAddress(), BigInteger.ZERO,
                                CallData.builder("propose").put("id", "p1").put("target", dao.contractAddress())
                                        .put("key", "member:" + d.address().toHex()).put("value", "true").build().encode(),
                                20_000, BigInteger.ONE), a),
                new BlockContext(2, 1_700_000_010L, new byte[32], miner, 1_000_000L, 1L));

        processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(0, dao.contractAddress(), BigInteger.ZERO,
                                CallData.builder("vote").put("id", "p1").build().encode(),
                                20_000, BigInteger.ONE), b),
                new BlockContext(3, 1_700_000_020L, new byte[32], miner, 1_000_000L, 1L));

        processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(0, dao.contractAddress(), BigInteger.ZERO,
                                CallData.builder("vote").put("id", "p1").build().encode(),
                                20_000, BigInteger.ONE), c),
                new BlockContext(4, 1_700_000_030L, new byte[32], miner, 1_000_000L, 1L));

        processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(2, dao.contractAddress(), BigInteger.ZERO,
                                CallData.builder("finalize").put("id", "p1").build().encode(),
                                20_000, BigInteger.ONE), a),
                new BlockContext(5, 1_700_000_040L, new byte[32], miner, 1_000_000L, 1L));

        TransactionReceipt proposalByNewMember = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(0, dao.contractAddress(), BigInteger.ZERO,
                                CallData.builder("propose").put("id", "p2").put("target", dao.contractAddress())
                                        .put("key", "hello").put("value", "world").build().encode(),
                                20_000, BigInteger.ONE), d),
                new BlockContext(6, 1_700_000_050L, new byte[32], miner, 1_000_000L, 1L));

        assertTrue(proposalByNewMember.success());
    }

    @Test
    void fileStorageRewardsValidMerkleProof() {
        TransactionProcessor processor = new TransactionProcessor(ContractCatalog.standardRegistry());
        WorldState state = new WorldState();
        Wallet owner = Wallet.create();
        Wallet storer = Wallet.create();
        Address miner = Address.random();
        state.getOrCreate(owner.address()).credit(Units.ETHER.multiply(BigInteger.TEN));
        state.getOrCreate(storer.address()).credit(Units.ETHER);

        byte[] leafA = "a".getBytes(StandardCharsets.UTF_8);
        byte[] leafB = "b".getBytes(StandardCharsets.UTF_8);
        byte[] root = Merkle.root(List.of(leafA, leafB));

        Transaction deploy = Transaction.createContract(
                0, BigInteger.valueOf(100),
                CallData.builder("native").put("id", "fileStorage")
                        .put("root", Hex.prefixed(root))
                        .put("leafCount", 2)
                        .put("reward", 10)
                        .put("interval", 1)
                        .build().encode(),
                20_000, BigInteger.ONE
        );
        TransactionReceipt fileStorage = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(deploy, owner),
                new BlockContext(1, 1_700_000_000L, new byte[32], miner, 1_000_000L, 1L));

        TransactionReceipt reward = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(0, fileStorage.contractAddress(), BigInteger.ZERO,
                                CallData.builder("prove")
                                        .put("index", 0)
                                        .put("leaf", Hex.prefixed(leafA))
                                        .putList("siblings", List.of(Hex.prefixed(dev.naoki.ethwhite.crypto.Keccak.hash(leafB))))
                                        .putList("path", List.of("0"))
                                        .build().encode(),
                                20_000, BigInteger.ONE), storer),
                new BlockContext(2, 1_700_000_010L, new byte[32], miner, 1_000_000L, 1L));

        assertTrue(reward.success());
        assertEquals(Units.ETHER.subtract(reward.feePaid()).add(BigInteger.TEN), state.getOrCreate(storer.address()).balance());
    }
}
