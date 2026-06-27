package dev.naoki.ethwhite.core;

import dev.naoki.ethwhite.contract.ContractContext;
import dev.naoki.ethwhite.contract.ContractCreationResult;
import dev.naoki.ethwhite.contract.ContractRegistry;
import dev.naoki.ethwhite.contract.MessageDispatcher;
import dev.naoki.ethwhite.contract.NativeContract;
import dev.naoki.ethwhite.crypto.Keccak;
import dev.naoki.ethwhite.ponzi.FundFlowEvent;
import dev.naoki.ethwhite.ponzi.FundFlowListener;
import dev.naoki.ethwhite.util.Rlp;
import dev.naoki.ethwhite.vm.VirtualMachine;

import java.math.BigInteger;
import java.util.List;

public final class TransactionProcessor {
    public static final long INTRINSIC_GAS = 21;
    public static final long DATA_GAS_PER_BYTE = 5;
    public static final int MAX_CALL_DEPTH = 16;

    private final ContractRegistry contractRegistry;
    private final VirtualMachine virtualMachine;
    private final FundFlowListener fundFlowListener;

    public TransactionProcessor(ContractRegistry contractRegistry) {
        this(contractRegistry, null);
    }

    public TransactionProcessor(ContractRegistry contractRegistry, FundFlowListener fundFlowListener) {
        this.contractRegistry = contractRegistry;
        this.virtualMachine = new VirtualMachine();
        this.fundFlowListener = fundFlowListener;
    }

    public TransactionReceipt apply(WorldState state, SignedTransaction signedTransaction, BlockContext blockContext) {
        Transaction transaction = signedTransaction.transaction();
        if (!signedTransaction.verify()) {
            throw new ExecutionException("Invalid transaction signature");
        }

        Address sender = signedTransaction.sender();
        Account senderAccount = state.getOrCreate(sender);
        if (senderAccount.nonce() != transaction.nonce()) {
            throw new ExecutionException("Invalid nonce");
        }

        BigInteger upfrontFee = transaction.gasPrice().multiply(BigInteger.valueOf(transaction.startGas()));
        if (senderAccount.balance().compareTo(upfrontFee) < 0) {
            throw new ExecutionException("Insufficient balance for upfront fee");
        }

        senderAccount.debit(upfrontFee);
        senderAccount.incrementNonce();
        WorldState snapshotAfterUpfrontCharge = state.copy();

        GasMeter intrinsicMeter = new GasMeter(transaction.startGas());
        try {
            intrinsicMeter.consume(INTRINSIC_GAS, "paying intrinsic transaction gas");
            intrinsicMeter.consume(Math.multiplyExact(DATA_GAS_PER_BYTE, transaction.data().length), "paying calldata byte gas");
        } catch (OutOfGasException exception) {
            creditMiner(state, blockContext.miner(), upfrontFee);
            return new TransactionReceipt(false, null, transaction.startGas(), upfrontFee, new byte[0], exception.getMessage());
        }

        ExecutionSession session = new ExecutionSession(state, blockContext, sender);
        MessageResult result;
        Address contractAddress = null;
        if (fundFlowListener != null) {
            state.beginTransferCapture(blockContext, Keccak.hash(signedTransaction.encode()));
        }
        try {
            if (transaction.isContractCreation()) {
                contractAddress = session.deployContract(sender, transaction.value(), transaction.data(), intrinsicMeter.remaining());
                result = MessageResult.success(session.lastGasRemaining(), new byte[0]);
            } else {
                result = session.call(sender, transaction.to(), transaction.value(), transaction.data(), intrinsicMeter.remaining(), 0);
            }
        } catch (RuntimeException exception) {
            state.clearTransferCapture();
            state.restore(snapshotAfterUpfrontCharge);
            creditMiner(state, blockContext.miner(), upfrontFee);
            return new TransactionReceipt(false, contractAddress, transaction.startGas(), upfrontFee, new byte[0], exception.getMessage());
        }

        if (!result.success()) {
            state.clearTransferCapture();
            state.restore(snapshotAfterUpfrontCharge);
            creditMiner(state, blockContext.miner(), upfrontFee);
            return new TransactionReceipt(false, contractAddress, transaction.startGas(), upfrontFee, new byte[0], result.error());
        }

        List<FundFlowEvent> capturedTransfers = state.capturedTransfers();
        state.clearTransferCapture();
        long gasRemaining = result.gasRemaining();
        long gasUsed = transaction.startGas() - gasRemaining;
        BigInteger refund = transaction.gasPrice().multiply(BigInteger.valueOf(gasRemaining));
        BigInteger minerFee = upfrontFee.subtract(refund);

        state.getOrCreate(sender).credit(refund);
        creditMiner(state, blockContext.miner(), minerFee);
        emitCapturedTransfers(capturedTransfers);

        return new TransactionReceipt(true, contractAddress, gasUsed, minerFee, result.returnData(), null);
    }

    private void creditMiner(WorldState state, Address miner, BigInteger amount) {
        if (amount.signum() > 0) {
            state.getOrCreate(miner).credit(amount);
        }
    }

    private void emitCapturedTransfers(List<FundFlowEvent> events) {
        if (fundFlowListener == null) {
            return;
        }
        for (FundFlowEvent event : events) {
            try {
                fundFlowListener.onTransfer(event);
            } catch (RuntimeException ignored) {
                // Detection is best-effort and must not affect transaction validity.
            }
        }
    }

    private final class ExecutionSession implements MessageDispatcher {
        private final WorldState state;
        private final BlockContext blockContext;
        private final Address origin;
        private long lastGasRemaining;

        private ExecutionSession(WorldState state, BlockContext blockContext, Address origin) {
            this.state = state;
            this.blockContext = blockContext;
            this.origin = origin;
        }

        private long lastGasRemaining() {
            return lastGasRemaining;
        }

        private Address deployContract(Address sender, BigInteger value, byte[] deploymentPayload, long gasLimit) {
            if (gasLimit < 0) {
                throw new ExecutionException("Negative gas limit");
            }
            Address contractAddress = contractAddress(sender, state.getOrCreate(sender).nonce() - 1);
            WorldState snapshot = state.copy();
            Account contractAccount = state.getOrCreate(contractAddress);
            contractAccount.setType(AccountType.CONTRACT);
            contractAccount.setCode(new byte[0]);
            contractAccount.setContractId(null);
            if (value.signum() > 0) {
                state.transfer(sender, contractAddress, value);
            }
            GasMeter gasMeter = new GasMeter(gasLimit);
            try {
                CallData callData = tryParseCallData(deploymentPayload);
                if (callData != null && "native".equals(callData.method())) {
                    String id = callData.arg("id");
                    if (!contractRegistry.contains(id)) {
                        throw new ExecutionException("Unknown native contract: " + id);
                    }
                    contractAccount.setContractId(id);
                    contractRegistry.nativeContract(id).onDeploy(new ContractContext(
                            state, blockContext, this, contractAddress, sender, origin, value, deploymentPayload, gasMeter, 0
                    ), callData);
                } else {
                    byte[] runtimeCode = virtualMachine.execute(deploymentPayload, new ContractContext(
                            state, blockContext, this, contractAddress, sender, origin, value, deploymentPayload, gasMeter, 0
                    ));
                    contractAccount.setCode(runtimeCode);
                }
            } catch (RuntimeException exception) {
                state.restore(snapshot);
                throw exception;
            }
            lastGasRemaining = gasMeter.remaining();
            return contractAddress;
        }

        @Override
        public MessageResult call(Address from, Address to, BigInteger value, byte[] data, long gasLimit, int depth) {
            if (depth > MAX_CALL_DEPTH) {
                return MessageResult.failure(0, "Maximum call depth exceeded");
            }
            WorldState snapshot = state.copy();
            GasMeter gasMeter = new GasMeter(gasLimit);
            try {
                if (value.signum() > 0) {
                    state.transfer(from, to, value);
                }
                Account account = state.getOrCreate(to);
                byte[] returnData = new byte[0];
                if (account.type() == AccountType.CONTRACT) {
                    ContractContext context = new ContractContext(state, blockContext, this, to, from, origin, value, data, gasMeter, depth);
                    if (account.contractId() != null) {
                        NativeContract nativeContract = contractRegistry.nativeContract(account.contractId());
                        returnData = nativeContract.onMessage(context, tryParseCallDataOrThrow(data));
                    } else {
                        returnData = virtualMachine.execute(account.code(), context);
                    }
                }
                lastGasRemaining = gasMeter.remaining();
                return MessageResult.success(gasMeter.remaining(), returnData);
            } catch (RuntimeException exception) {
                state.restore(snapshot);
                lastGasRemaining = 0;
                return MessageResult.failure(0, exception.getMessage());
            }
        }

        @Override
        public ContractCreationResult create(Address creator, BigInteger value, byte[] initCode, long gasLimit, int depth) {
            if (depth > MAX_CALL_DEPTH) {
                return ContractCreationResult.failure(0, "Maximum call depth exceeded");
            }
            WorldState snapshot = state.copy();
            GasMeter gasMeter = new GasMeter(gasLimit);
            try {
                Account creatorAccount = state.getOrCreate(creator);
                long creatorNonce = creatorAccount.nonce();
                creatorAccount.incrementNonce();
                Address contractAddress = contractAddress(creator, creatorNonce);
                Account contractAccount = state.getOrCreate(contractAddress);
                contractAccount.setType(AccountType.CONTRACT);
                contractAccount.setCode(new byte[0]);
                contractAccount.setContractId(null);
                if (value.signum() > 0) {
                    state.transfer(creator, contractAddress, value);
                }

                CallData callData = tryParseCallData(initCode);
                if (callData != null && "native".equals(callData.method())) {
                    String id = callData.arg("id");
                    if (!contractRegistry.contains(id)) {
                        throw new ExecutionException("Unknown native contract: " + id);
                    }
                    contractAccount.setContractId(id);
                    contractRegistry.nativeContract(id).onDeploy(new ContractContext(
                            state, blockContext, this, contractAddress, creator, origin, value, initCode, gasMeter, depth
                    ), callData);
                } else {
                    byte[] runtimeCode = virtualMachine.execute(initCode, new ContractContext(
                            state, blockContext, this, contractAddress, creator, origin, value, initCode, gasMeter, depth
                    ));
                    contractAccount.setCode(runtimeCode);
                }
                lastGasRemaining = gasMeter.remaining();
                return ContractCreationResult.success(contractAddress, gasMeter.remaining());
            } catch (RuntimeException exception) {
                state.restore(snapshot);
                lastGasRemaining = 0;
                return ContractCreationResult.failure(0, exception.getMessage());
            }
        }
    }

    private static CallData tryParseCallData(byte[] payload) {
        try {
            return CallData.parse(payload);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static CallData tryParseCallDataOrThrow(byte[] payload) {
        try {
            return CallData.parse(payload);
        } catch (RuntimeException exception) {
            throw new ExecutionException("Invalid native contract calldata", exception);
        }
    }

    private static Address contractAddress(Address sender, long nonce) {
        byte[] hash = Keccak.hash(Rlp.encodeList(
                Rlp.encodeAddress(sender),
                Rlp.encodeLong(nonce)
        ));
        return Address.fromBytes(java.util.Arrays.copyOfRange(hash, 12, 32));
    }
}
