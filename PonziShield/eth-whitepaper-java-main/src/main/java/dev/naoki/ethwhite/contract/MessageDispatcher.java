package dev.naoki.ethwhite.contract;

import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.MessageResult;

import java.math.BigInteger;

public interface MessageDispatcher {
    MessageResult call(Address from, Address to, BigInteger value, byte[] data, long gasLimit, int depth);

    ContractCreationResult create(Address creator, BigInteger value, byte[] initCode, long gasLimit, int depth);

    default MessageResult call(Address from, Address to, BigInteger value, CallData callData, long gasLimit, int depth) {
        return call(from, to, value, callData.encode(), gasLimit, depth);
    }
}
