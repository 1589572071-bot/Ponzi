package dev.naoki.ethwhite.sample;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.contract.NativeContract;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.CallData;
import dev.naoki.ethwhite.core.ExecutionException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

abstract class AbstractNativeContract implements NativeContract {
    protected static final byte[] OK = "ok".getBytes(StandardCharsets.UTF_8);

    protected static BigInteger big(CallData callData, String key) {
        return new BigInteger(callData.arg(key));
    }

    protected static BigInteger bigOrDefault(CallData callData, String key, BigInteger defaultValue) {
        String value = callData.argOrDefault(key, defaultValue.toString());
        return new BigInteger(value);
    }

    protected static BigInteger metadataBig(ContractContext context, String key) {
        String value = context.metadata(key);
        return value == null ? BigInteger.ZERO : new BigInteger(value);
    }

    protected static void putBig(ContractContext context, String key, BigInteger value) {
        if (value.signum() < 0) {
            throw new ExecutionException("Negative value not allowed");
        }
        context.putMetadata(key, value.toString());
    }

    protected static Address address(CallData callData, String key) {
        return Address.fromHex(callData.arg(key));
    }

    protected static Address metadataAddress(ContractContext context, String key) {
        String value = context.metadata(key);
        return value == null ? null : Address.fromHex(value);
    }

    protected static void putAddress(ContractContext context, String key, Address address) {
        context.putMetadata(key, address.toHex());
    }

    protected static boolean metadataFlag(ContractContext context, String key) {
        return Boolean.parseBoolean(context.metadata(key));
    }

    protected static void putFlag(ContractContext context, String key, boolean value) {
        context.putMetadata(key, Boolean.toString(value));
    }

    protected static byte[] response(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    protected static List<Address> addressList(CallData callData, String key) {
        List<Address> addresses = new ArrayList<>();
        for (String raw : callData.list(key)) {
            if (!raw.isBlank()) {
                addresses.add(Address.fromHex(raw));
            }
        }
        return addresses;
    }

    protected static void require(boolean condition, String message) {
        if (!condition) {
            throw new ExecutionException(message);
        }
    }
}
