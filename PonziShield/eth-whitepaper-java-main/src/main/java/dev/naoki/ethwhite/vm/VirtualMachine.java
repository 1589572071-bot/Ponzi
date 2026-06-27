package dev.naoki.ethwhite.vm;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.contract.ContractCreationResult;
import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.ExecutionException;
import dev.naoki.ethwhite.core.MessageResult;
import dev.naoki.ethwhite.core.Word;
import dev.naoki.ethwhite.crypto.Keccak;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public final class VirtualMachine {
    public byte[] execute(byte[] code, ContractContext context) {
        Deque<Word> stack = new ArrayDeque<>();
        byte[] memory = new byte[64];
        int pc = 0;
        while (pc < code.length) {
            int current = code[pc] & 0xff;
            if (OpCode.isPush(current)) {
                int width = current - 0x5f;
                context.gasMeter().consume(3, "executing PUSH");
                pc++;
                if (pc + width > code.length) {
                    throw new ExecutionException("PUSH exceeds code bounds");
                }
                stack.push(Word.fromBytes(Arrays.copyOfRange(code, pc, pc + width)));
                pc += width;
                continue;
            }
            if (OpCode.isDup(current)) {
                int index = current - 0x7f;
                context.gasMeter().consume(2, "executing DUP");
                duplicate(stack, index);
                pc++;
                continue;
            }
            if (OpCode.isSwap(current)) {
                int index = current - 0x8f;
                context.gasMeter().consume(3, "executing SWAP");
                swap(stack, index);
                pc++;
                continue;
            }

            OpCode opCode = OpCode.fromByte(current);
            if (opCode == null) {
                throw new ExecutionException("Unknown opcode 0x" + Integer.toHexString(current));
            }
            context.gasMeter().consume(opCode.gasCost(), "executing " + opCode.name());
            switch (opCode) {
                case STOP -> {
                    return new byte[0];
                }
                case ADD -> stack.push(binary(stack, Word::add));
                case MUL -> stack.push(binary(stack, Word::mul));
                case SUB -> stack.push(binary(stack, Word::sub));
                case DIV -> stack.push(binary(stack, Word::div));
                case SHA3 -> {
                    int offset = pop(stack).toIntExact();
                    int length = pop(stack).toIntExact();
                    memory = ensureSize(memory, offset + length);
                    stack.push(Word.fromBytes(Keccak.hash(Arrays.copyOfRange(memory, offset, offset + length))));
                }
                case LT -> compare(stack, orderedComparison(stack) < 0);
                case GT -> compare(stack, orderedComparison(stack) > 0);
                case EQ -> compare(stack, orderedEquality(stack));
                case ISZERO -> compare(stack, pop(stack).isZero());
                case AND -> stack.push(Word.fromBytes(and(pop(stack).toBytes(), pop(stack).toBytes())));
                case OR -> stack.push(Word.fromBytes(or(pop(stack).toBytes(), pop(stack).toBytes())));
                case ADDRESS -> stack.push(Word.fromBytes(leftPadAddress(context.self())));
                case BALANCE -> {
                    Address address = Address.fromBytes(Arrays.copyOfRange(pop(stack).toBytes(), 12, 32));
                    stack.push(Word.of(context.state().getOrCreate(address).balance()));
                }
                case ORIGIN -> stack.push(Word.fromBytes(leftPadAddress(context.origin())));
                case CALLER -> stack.push(Word.fromBytes(leftPadAddress(context.sender())));
                case CALLVALUE -> stack.push(Word.of(context.value()));
                case CALLDATALOAD -> {
                    int offset = pop(stack).toIntExact();
                    byte[] data = context.data();
                    byte[] slice = new byte[Word.SIZE];
                    if (offset < data.length) {
                        System.arraycopy(data, offset, slice, 0, Math.min(Word.SIZE, data.length - offset));
                    }
                    stack.push(Word.fromBytes(slice));
                }
                case CALLDATASIZE -> stack.push(Word.of(context.data().length));
                case CALLDATACOPY -> {
                    int memoryOffset = pop(stack).toIntExact();
                    int dataOffset = pop(stack).toIntExact();
                    int length = pop(stack).toIntExact();
                    memory = ensureSize(memory, memoryOffset + length);
                    copyRange(context.data(), dataOffset, memory, memoryOffset, length);
                }
                case CODESIZE -> stack.push(Word.of(code.length));
                case CODECOPY -> {
                    int memoryOffset = pop(stack).toIntExact();
                    int codeOffset = pop(stack).toIntExact();
                    int length = pop(stack).toIntExact();
                    memory = ensureSize(memory, memoryOffset + length);
                    copyRange(code, codeOffset, memory, memoryOffset, length);
                }
                case PREVHASH -> stack.push(Word.fromBytes(context.block().previousBlockHash()));
                case TIMESTAMP -> stack.push(Word.of(context.block().timestamp()));
                case NUMBER -> stack.push(Word.of(context.block().number()));
                case POP -> pop(stack);
                case MLOAD -> {
                    int offset = pop(stack).toIntExact();
                    memory = ensureSize(memory, offset + Word.SIZE);
                    stack.push(Word.fromBytes(Arrays.copyOfRange(memory, offset, offset + Word.SIZE)));
                }
                case MSTORE -> {
                    int offset = pop(stack).toIntExact();
                    Word value = pop(stack);
                    memory = ensureSize(memory, offset + Word.SIZE);
                    System.arraycopy(value.toBytes(), 0, memory, offset, Word.SIZE);
                }
                case MSTORE8 -> {
                    int offset = pop(stack).toIntExact();
                    Word value = pop(stack);
                    memory = ensureSize(memory, offset + 1);
                    memory[offset] = value.toBytes()[Word.SIZE - 1];
                }
                case SLOAD -> {
                    Word key = pop(stack);
                    stack.push(context.storageWord(key.toBigInteger()));
                }
                case SSTORE -> {
                    Word key = pop(stack);
                    Word value = pop(stack);
                    context.putStorageWord(key.toBigInteger(), value);
                }
                case JUMP -> {
                    pc = validateJumpDestination(code, pop(stack).toIntExact());
                    continue;
                }
                case JUMPI -> {
                    int destination = pop(stack).toIntExact();
                    Word condition = pop(stack);
                    if (!condition.isZero()) {
                        pc = validateJumpDestination(code, destination);
                        continue;
                    }
                }
                case PC -> stack.push(Word.of(pc));
                case MSIZE -> stack.push(Word.of(memory.length));
                case GAS -> stack.push(Word.of(context.gasMeter().remaining()));
                case JUMPDEST -> {
                    // Marker opcode for validated jump targets.
                }
                case CREATE -> {
                    BigInteger value = pop(stack).toBigInteger();
                    int offset = pop(stack).toIntExact();
                    int length = pop(stack).toIntExact();
                    memory = ensureSize(memory, offset + length);
                    byte[] initCode = Arrays.copyOfRange(memory, offset, offset + length);
                    ContractCreationResult result = context.create(value, initCode, context.gasMeter().remaining());
                    stack.push(result.success() ? Word.fromBytes(leftPadAddress(result.address())) : Word.ZERO);
                }
                case CALL -> {
                    long gas = pop(stack).toLongExact();
                    Address to = Address.fromBytes(Arrays.copyOfRange(pop(stack).toBytes(), 12, 32));
                    BigInteger value = pop(stack).toBigInteger();
                    int inputOffset = pop(stack).toIntExact();
                    int inputLength = pop(stack).toIntExact();
                    int outputOffset = pop(stack).toIntExact();
                    int outputLength = pop(stack).toIntExact();
                    memory = ensureSize(memory, Math.max(inputOffset + inputLength, outputOffset + outputLength));
                    byte[] payload = Arrays.copyOfRange(memory, inputOffset, inputOffset + inputLength);
                    MessageResult result = context.call(to, value, payload, gas);
                    writeReturnData(memory, outputOffset, outputLength, result.returnData());
                    stack.push(result.success() ? Word.ONE : Word.ZERO);
                }
                case RETURN -> {
                    int offset = pop(stack).toIntExact();
                    int length = pop(stack).toIntExact();
                    memory = ensureSize(memory, offset + length);
                    return Arrays.copyOfRange(memory, offset, offset + length);
                }
                case REVERT -> throw new ExecutionException("Contract execution reverted");
            }
            pc++;
        }
        return new byte[0];
    }

    private static Word binary(Deque<Word> stack, java.util.function.BiFunction<Word, Word, Word> operation) {
        Word right = pop(stack);
        Word left = pop(stack);
        return operation.apply(left, right);
    }

    private static Word pop(Deque<Word> stack) {
        Word word = stack.poll();
        if (word == null) {
            throw new ExecutionException("Stack underflow");
        }
        return word;
    }

    private static void duplicate(Deque<Word> stack, int index) {
        Word[] words = stack.toArray(Word[]::new);
        if (index <= 0 || index > words.length) {
            throw new ExecutionException("Invalid DUP index");
        }
        stack.push(words[index - 1]);
    }

    private static void swap(Deque<Word> stack, int index) {
        Word[] words = stack.toArray(Word[]::new);
        if (index <= 0 || index >= words.length) {
            throw new ExecutionException("Invalid SWAP index");
        }
        Word top = words[0];
        words[0] = words[index];
        words[index] = top;
        stack.clear();
        for (int i = words.length - 1; i >= 0; i--) {
            stack.push(words[i]);
        }
    }

    private static void compare(Deque<Word> stack, boolean condition) {
        stack.push(condition ? Word.ONE : Word.ZERO);
    }

    private static int orderedComparison(Deque<Word> stack) {
        Word right = pop(stack);
        Word left = pop(stack);
        return left.compareTo(right);
    }

    private static boolean orderedEquality(Deque<Word> stack) {
        Word right = pop(stack);
        Word left = pop(stack);
        return left.equals(right);
    }

    private static byte[] ensureSize(byte[] memory, int size) {
        if (size <= memory.length) {
            return memory;
        }
        int nextSize = Math.max(size, memory.length * 2);
        return Arrays.copyOf(memory, nextSize);
    }

    private static byte[] leftPadAddress(Address address) {
        byte[] out = new byte[Word.SIZE];
        byte[] addressBytes = address.toBytes();
        System.arraycopy(addressBytes, 0, out, Word.SIZE - addressBytes.length, addressBytes.length);
        return out;
    }

    private static byte[] and(byte[] a, byte[] b) {
        byte[] out = new byte[Math.max(a.length, b.length)];
        byte[] left = pad(out.length, a);
        byte[] right = pad(out.length, b);
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (left[i] & right[i]);
        }
        return out;
    }

    private static byte[] or(byte[] a, byte[] b) {
        byte[] out = new byte[Math.max(a.length, b.length)];
        byte[] left = pad(out.length, a);
        byte[] right = pad(out.length, b);
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (left[i] | right[i]);
        }
        return out;
    }

    private static byte[] pad(int size, byte[] input) {
        byte[] out = new byte[size];
        System.arraycopy(input, 0, out, size - input.length, input.length);
        return out;
    }

    private static int validateJumpDestination(byte[] code, int destination) {
        if (destination < 0 || destination >= code.length) {
            throw new ExecutionException("Jump destination out of bounds");
        }
        if ((code[destination] & 0xff) != OpCode.JUMPDEST.code()) {
            throw new ExecutionException("Jump destination must point to JUMPDEST");
        }
        return destination;
    }

    private static void copyRange(byte[] source, int sourceOffset, byte[] target, int targetOffset, int length) {
        Arrays.fill(target, targetOffset, targetOffset + length, (byte) 0);
        if (length == 0 || sourceOffset >= source.length) {
            return;
        }
        int actual = Math.min(length, source.length - Math.max(sourceOffset, 0));
        if (actual > 0) {
            System.arraycopy(source, Math.max(sourceOffset, 0), target, targetOffset, actual);
        }
    }

    private static void writeReturnData(byte[] memory, int offset, int length, byte[] returnData) {
        Arrays.fill(memory, offset, offset + length, (byte) 0);
        System.arraycopy(returnData, 0, memory, offset, Math.min(length, returnData.length));
    }
}
