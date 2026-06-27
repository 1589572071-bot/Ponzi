package dev.naoki.ethwhite.vm;

import java.util.HashMap;
import java.util.Map;

public enum OpCode {
    STOP(0x00, 0),
    ADD(0x01, 1),
    MUL(0x02, 2),
    SUB(0x03, 1),
    DIV(0x04, 2),
    SHA3(0x20, 30),
    ADDRESS(0x30, 2),
    LT(0x10, 1),
    GT(0x11, 1),
    EQ(0x14, 1),
    ISZERO(0x15, 1),
    AND(0x16, 1),
    OR(0x17, 1),
    BALANCE(0x31, 5),
    ORIGIN(0x32, 2),
    CALLER(0x33, 2),
    CALLVALUE(0x34, 2),
    CALLDATALOAD(0x35, 3),
    CALLDATASIZE(0x36, 2),
    CALLDATACOPY(0x37, 3),
    CODESIZE(0x38, 2),
    CODECOPY(0x39, 3),
    PREVHASH(0x40, 2),
    TIMESTAMP(0x42, 2),
    NUMBER(0x43, 2),
    POP(0x50, 1),
    MLOAD(0x51, 3),
    MSTORE(0x52, 3),
    MSTORE8(0x53, 3),
    SLOAD(0x54, 50),
    SSTORE(0x55, 200),
    JUMP(0x56, 8),
    JUMPI(0x57, 10),
    PC(0x58, 2),
    MSIZE(0x59, 2),
    GAS(0x5a, 2),
    JUMPDEST(0x5b, 1),
    CREATE(0xf0, 320),
    CALL(0xf1, 40),
    RETURN(0xf3, 0),
    REVERT(0xfd, 0);

    private static final Map<Integer, OpCode> INDEX = new HashMap<>();

    static {
        for (OpCode opCode : values()) {
            INDEX.put(opCode.code, opCode);
        }
    }

    private final int code;
    private final long gasCost;

    OpCode(int code, long gasCost) {
        this.code = code;
        this.gasCost = gasCost;
    }

    public int code() {
        return code;
    }

    public long gasCost() {
        return gasCost;
    }

    public static OpCode fromByte(int value) {
        return INDEX.get(value);
    }

    public static boolean isPush(int value) {
        return value >= 0x60 && value <= 0x7f;
    }

    public static boolean isDup(int value) {
        return value >= 0x80 && value <= 0x8f;
    }

    public static boolean isSwap(int value) {
        return value >= 0x90 && value <= 0x9f;
    }
}
