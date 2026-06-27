package dev.naoki.ethwhite;

import dev.naoki.ethwhite.contract.ContractRegistry;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.BlockContext;
import dev.naoki.ethwhite.core.Transaction;
import dev.naoki.ethwhite.core.TransactionProcessor;
import dev.naoki.ethwhite.core.TransactionReceipt;
import dev.naoki.ethwhite.core.Units;
import dev.naoki.ethwhite.core.WorldState;
import dev.naoki.ethwhite.crypto.Wallet;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TransactionProcessorTest {
    @Test
    void etherTransferConsumesIntrinsicGasAndRefundsRest() {
        TransactionProcessor processor = new TransactionProcessor(new ContractRegistry());
        WorldState state = new WorldState();
        Wallet sender = Wallet.create();
        Address recipient = Address.random();
        Address miner = Address.random();
        state.getOrCreate(sender.address()).credit(Units.ETHER.multiply(BigInteger.TEN));

        Transaction transaction = new Transaction(0, recipient, Units.ETHER.multiply(BigInteger.TWO), new byte[0], 1_000, BigInteger.ONE);
        TransactionReceipt receipt = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(transaction, sender),
                new BlockContext(1, 1_700_000_000L, new byte[32], miner, 1_000_000L, 1L));

        assertTrue(receipt.success());
        assertEquals(TransactionProcessor.INTRINSIC_GAS, receipt.gasUsed());
        assertEquals(Units.ETHER.multiply(BigInteger.TWO), state.getOrCreate(recipient).balance());
        assertEquals(BigInteger.valueOf(TransactionProcessor.INTRINSIC_GAS), state.getOrCreate(miner).balance());
    }

    @Test
    void successfulEtherTransferEmitsFundFlowEvent() {
        List<dev.naoki.ethwhite.ponzi.FundFlowEvent> events = new ArrayList<>();
        TransactionProcessor processor = new TransactionProcessor(new ContractRegistry(), events::add);
        WorldState state = new WorldState();
        Wallet sender = Wallet.create();
        Address recipient = Address.random();
        Address miner = Address.random();
        state.getOrCreate(sender.address()).credit(Units.ETHER.multiply(BigInteger.TEN));

        Transaction transaction = new Transaction(0, recipient, BigInteger.valueOf(123), new byte[0], 1_000, BigInteger.ONE);
        TransactionReceipt receipt = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(transaction, sender),
                new BlockContext(7, 1_700_000_700L, new byte[32], miner, 1_000_000L, 1L));

        assertTrue(receipt.success());
        assertEquals(1, events.size());
        dev.naoki.ethwhite.ponzi.FundFlowEvent event = events.getFirst();
        assertEquals(sender.address(), event.from());
        assertEquals(recipient, event.to());
        assertEquals(BigInteger.valueOf(123), event.value());
        assertEquals(7L, event.blockNumber());
        assertEquals(1_700_000_700L, event.timestamp());
    }

    @Test
    void failedVmExecutionRevertsStateButBurnsGas() {
        TransactionProcessor processor = new TransactionProcessor(new ContractRegistry());
        WorldState state = new WorldState();
        Wallet owner = Wallet.create();
        Address miner = Address.random();
        state.getOrCreate(owner.address()).credit(Units.ETHER.multiply(BigInteger.TEN));

        byte[] bytecode = new byte[] {
                0x65, 0x60, 0x2a, 0x60, 0x01, 0x55, 0x00,
                0x60, 0x00,
                0x52,
                0x60, 0x06,
                0x60, 0x1a,
                (byte) 0xf3
        };
        Transaction deploy = Transaction.createContract(0, BigInteger.ZERO, bytecode, 1_000, BigInteger.ONE);
        TransactionReceipt deployReceipt = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(deploy, owner),
                new BlockContext(1, 1_700_000_000L, new byte[32], miner, 1_000_000L, 1L));
        assertTrue(deployReceipt.success());

        Transaction invoke = new Transaction(1, deployReceipt.contractAddress(), BigInteger.ZERO, new byte[0], 100, BigInteger.ONE);
        TransactionReceipt failure = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(invoke, owner),
                new BlockContext(2, 1_700_000_030L, new byte[32], miner, 1_000_000L, 1L));

        assertFalse(failure.success());
        assertEquals(BigInteger.ZERO, state.getOrCreate(deployReceipt.contractAddress()).storage().getOrDefault(BigInteger.ONE, dev.naoki.ethwhite.core.Word.ZERO).toBigInteger());
        assertEquals(2L, state.getOrCreate(owner.address()).nonce());
        assertEquals(BigInteger.valueOf(100 + deployReceipt.gasUsed()), state.getOrCreate(miner).balance());
    }

    @Test
    void failedValueTransferToContractDoesNotEmitFundFlowEvent() {
        List<dev.naoki.ethwhite.ponzi.FundFlowEvent> events = new ArrayList<>();
        TransactionProcessor processor = new TransactionProcessor(new ContractRegistry(), events::add);
        WorldState state = new WorldState();
        Wallet owner = Wallet.create();
        Address miner = Address.random();
        state.getOrCreate(owner.address()).credit(Units.ETHER.multiply(BigInteger.TEN));

        byte[] runtimeCode = new byte[] {
                0x60, 0x00,
                0x52,
                0x60, 0x20,
                0x60, 0x00,
                (byte) 0xf3
        };
        TransactionReceipt deployReceipt = processor.apply(state,
                dev.naoki.ethwhite.core.SignedTransaction.sign(
                        Transaction.createContract(0, BigInteger.ZERO, TestSupport.initCodeReturning(runtimeCode), 2_000, BigInteger.ONE),
                        owner),
                new BlockContext(1, 1_700_000_000L, new byte[32], miner, 1_000_000L, 1L));
        assertTrue(deployReceipt.success());

        Transaction failingCall = new Transaction(1, deployReceipt.contractAddress(), BigInteger.valueOf(10), new byte[0], 40, BigInteger.ONE);
        TransactionReceipt failure = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(failingCall, owner),
                new BlockContext(2, 1_700_000_010L, new byte[32], miner, 1_000_000L, 1L));

        assertFalse(failure.success());
        assertTrue(events.isEmpty());
    }

    @Test
    void invalidNonceIsRejected() {
        TransactionProcessor processor = new TransactionProcessor(new ContractRegistry());
        WorldState state = new WorldState();
        Wallet sender = Wallet.create();
        state.getOrCreate(sender.address()).credit(Units.ETHER);

        Transaction transaction = new Transaction(1, Address.random(), BigInteger.ONE, new byte[0], 100, BigInteger.ONE);

        assertThrows(RuntimeException.class, () -> processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(transaction, sender),
                new BlockContext(1, 1_700_000_000L, new byte[32], Address.random(), 1_000_000L, 1L)));
    }

    @Test
    void contractCreationRunsInitCodeAndInstallsRuntime() {
        TransactionProcessor processor = new TransactionProcessor(new ContractRegistry());
        WorldState state = new WorldState();
        Wallet owner = Wallet.create();
        Address miner = Address.random();
        state.getOrCreate(owner.address()).credit(Units.ETHER.multiply(BigInteger.TEN));

        byte[] runtimeCode = new byte[] {
                0x60, 0x2a,
                0x60, 0x01,
                0x55,
                0x00
        };
        Transaction deploy = Transaction.createContract(0, BigInteger.ZERO, TestSupport.initCodeReturning(runtimeCode), 2_000, BigInteger.ONE);

        TransactionReceipt receipt = processor.apply(state, dev.naoki.ethwhite.core.SignedTransaction.sign(deploy, owner),
                new BlockContext(1, 1_700_000_000L, new byte[32], miner, 1_000_000L, 1L));

        assertTrue(receipt.success());
        assertArrayEquals(runtimeCode, state.getOrCreate(receipt.contractAddress()).code());
    }

    @Test
    void vmCallOpcodeCanInvokeAnotherContract() {
        TransactionProcessor processor = new TransactionProcessor(new ContractRegistry());
        WorldState state = new WorldState();
        Wallet owner = Wallet.create();
        Address miner = Address.random();
        state.getOrCreate(owner.address()).credit(Units.ETHER.multiply(BigInteger.TEN));

        byte[] calleeRuntime = new byte[] {
                0x60, 0x2a,
                0x60, 0x00,
                0x52,
                0x60, 0x20,
                0x60, 0x00,
                (byte) 0xf3
        };
        TransactionReceipt callee = processor.apply(state,
                dev.naoki.ethwhite.core.SignedTransaction.sign(
                        Transaction.createContract(0, BigInteger.ZERO, TestSupport.initCodeReturning(calleeRuntime), 3_000, BigInteger.ONE),
                        owner),
                new BlockContext(1, 1_700_000_000L, new byte[32], miner, 1_000_000L, 1L));

        byte[] address = callee.contractAddress().toBytes();
        byte[] parentRuntime = new byte[] {
                0x60, 0x20,
                0x60, 0x00,
                0x60, 0x00,
                0x60, 0x00,
                0x60, 0x00,
                0x73, address[0], address[1], address[2], address[3], address[4], address[5], address[6], address[7], address[8], address[9],
                address[10], address[11], address[12], address[13], address[14], address[15], address[16], address[17], address[18], address[19],
                0x61, 0x03, (byte) 0xe8,
                (byte) 0xf1,
                0x50,
                0x60, 0x00,
                0x51,
                0x60, 0x01,
                0x55,
                0x00
        };
        TransactionReceipt parent = processor.apply(state,
                dev.naoki.ethwhite.core.SignedTransaction.sign(
                        Transaction.createContract(1, BigInteger.ZERO, TestSupport.initCodeReturning(parentRuntime), 5_000, BigInteger.ONE),
                        owner),
                new BlockContext(2, 1_700_000_010L, new byte[32], miner, 1_000_000L, 1L));

        TransactionReceipt invoke = processor.apply(state,
                dev.naoki.ethwhite.core.SignedTransaction.sign(
                        new Transaction(2, parent.contractAddress(), BigInteger.ZERO, new byte[0], 10_000, BigInteger.ONE),
                        owner),
                new BlockContext(3, 1_700_000_020L, new byte[32], miner, 1_000_000L, 1L));

        assertTrue(invoke.success());
        assertEquals(BigInteger.valueOf(42), state.getOrCreate(parent.contractAddress()).storage().get(BigInteger.ONE).toBigInteger());
    }
}
