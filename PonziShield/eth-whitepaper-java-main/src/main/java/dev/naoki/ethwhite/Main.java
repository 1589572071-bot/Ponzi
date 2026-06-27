package dev.naoki.ethwhite;

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
import java.nio.charset.StandardCharsets;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        TransactionProcessor processor = new TransactionProcessor(ContractCatalog.standardRegistry());
        WorldState state = new WorldState();
        Wallet owner = Wallet.create();
        Wallet recipient = Wallet.create();
        Address miner = Address.random();

        state.getOrCreate(owner.address()).credit(Units.ETHER.multiply(BigInteger.TEN));

        Transaction deploy = Transaction.createContract(
                0,
                BigInteger.ZERO,
                CallData.builder("native")
                        .put("id", "token")
                        .put("owner", owner.address())
                        .put("supply", 1_000)
                        .build()
                        .encode(),
                10_000,
                BigInteger.ONE
        );
        TransactionReceipt tokenDeployment = processor.apply(
                state,
                SignedTransaction.sign(deploy, owner),
                new BlockContext(1, 1_700_000_000L, new byte[32], miner, 1_000_000L, 1L)
        );

        Transaction transfer = new Transaction(
                1,
                tokenDeployment.contractAddress(),
                BigInteger.ZERO,
                CallData.builder("transfer")
                        .put("to", recipient.address())
                        .put("amount", 125)
                        .build()
                        .encode(),
                10_000,
                BigInteger.ONE
        );
        processor.apply(
                state,
                SignedTransaction.sign(transfer, owner),
                new BlockContext(2, 1_700_000_010L, new byte[32], miner, 1_000_000L, 1L)
        );

        state.getOrCreate(recipient.address()).credit(Units.ETHER);
        Transaction query = new Transaction(
                0,
                tokenDeployment.contractAddress(),
                BigInteger.ZERO,
                CallData.builder("balanceOf").put("owner", recipient.address()).build().encode(),
                10_000,
                BigInteger.ONE
        );
        TransactionReceipt balance = processor.apply(
                state,
                SignedTransaction.sign(query, recipient),
                new BlockContext(3, 1_700_000_020L, new byte[32], miner, 1_000_000L, 1L)
        );

        System.out.println("Token contract: " + tokenDeployment.contractAddress());
        System.out.println("Recipient token balance: " + new String(balance.returnData(), StandardCharsets.UTF_8));
        System.out.println("Miner fees collected: " + state.getOrCreate(miner).balance());
    }
}
