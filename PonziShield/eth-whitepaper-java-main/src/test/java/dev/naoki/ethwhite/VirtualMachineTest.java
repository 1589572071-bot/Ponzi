package dev.naoki.ethwhite;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.contract.ContractCreationResult;
import dev.naoki.ethwhite.contract.MessageDispatcher;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.BlockContext;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.GasMeter;
import dev.naoki.ethwhite.core.MessageResult;
import dev.naoki.ethwhite.core.Word;
import dev.naoki.ethwhite.core.WorldState;
import dev.naoki.ethwhite.vm.VirtualMachine;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VirtualMachineTest {
    @Test
    void vmCanStoreLoadAndReturnWord() {
        WorldState state = new WorldState();
        Address contract = Address.random();
        state.getOrCreate(contract).setType(dev.naoki.ethwhite.core.AccountType.CONTRACT);
        byte[] code = new byte[] {
                0x60, 0x2a,
                0x60, 0x01,
                0x55,
                0x60, 0x01,
                0x54,
                0x60, 0x00,
                0x52,
                0x60, 0x20,
                0x60, 0x00,
                (byte) 0xf3
        };
        ContractContext context = new ContractContext(
                state,
                new BlockContext(1, 1_700_000_000L, new byte[32], Address.random(), 1_000_000L, 1L),
                new MessageDispatcher() {
                    @Override
                    public MessageResult call(Address from, Address to, BigInteger value, byte[] data, long gasLimit, int depth) {
                        return MessageResult.success(gasLimit, new byte[0]);
                    }

                    @Override
                    public ContractCreationResult create(Address creator, BigInteger value, byte[] initCode, long gasLimit, int depth) {
                        return ContractCreationResult.failure(gasLimit, "not supported in test");
                    }
                },
                contract,
                Address.random(),
                Address.random(),
                BigInteger.ZERO,
                new byte[0],
                new GasMeter(10_000),
                0
        );

        byte[] result = new VirtualMachine().execute(code, context);

        assertEquals(BigInteger.valueOf(42), state.getOrCreate(contract).storage().get(BigInteger.ONE).toBigInteger());
        assertEquals(BigInteger.valueOf(42), Word.fromBytes(result).toBigInteger());
    }
}
