package dev.naoki.ethwhite.core;

public final class ProofOfWork {
    private ProofOfWork() {
    }

    public static BlockHeader mine(BlockHeader template, long maxAttempts) {
        for (long nonce = 0; nonce < maxAttempts; nonce++) {
            BlockHeader candidate = new BlockHeader(
                    template.parentHash(),
                    template.stateRoot(),
                    template.transactionsRoot(),
                    template.unclesRoot(),
                    template.miner(),
                    template.number(),
                    template.timestamp(),
                    template.difficulty(),
                    template.gasLimit(),
                    template.gasUsed(),
                    nonce
            );
            if (candidate.validProofOfWork()) {
                return candidate;
            }
        }
        throw new ExecutionException("Unable to mine block within attempt limit");
    }
}
