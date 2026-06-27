package dev.naoki.ethwhite;

import dev.naoki.ethwhite.core.Address;
import dev.naoki.ethwhite.core.Block;
import dev.naoki.ethwhite.core.Blockchain;
import dev.naoki.ethwhite.core.BlockProcessor;
import dev.naoki.ethwhite.core.WorldState;
import dev.naoki.ethwhite.sample.ContractCatalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class BlockchainTest {
    @Test
    void ghostPrefersHeavierObservedSubtree() {
        Blockchain chain = new Blockchain(ContractCatalog.standardRegistry());
        WorldState genesisState = new WorldState();
        Address minerA = Address.random();
        Address minerB = Address.random();
        Address minerC = Address.random();

        Block genesis = TestSupport.mineBlock(null, genesisState, minerA, List.of(), List.of(), ContractCatalog.standardRegistry(), 1_700_000_000L);
        chain.addGenesis(genesis, genesisState);

        WorldState genesisApplied = chain.stateAt(genesis);
        Block branchA1 = TestSupport.mineBlock(genesis, genesisApplied, minerA, List.of(), List.of(), ContractCatalog.standardRegistry(), 1_700_000_010L);
        Block branchB1 = TestSupport.mineBlock(genesis, genesisApplied, minerB, List.of(), List.of(), ContractCatalog.standardRegistry(), 1_700_000_011L);
        chain.addBlock(branchA1);
        chain.addBlock(branchB1);

        Block branchB2 = TestSupport.mineBlock(branchB1, chain.stateAt(branchB1), minerC, List.of(), List.of(), ContractCatalog.standardRegistry(), 1_700_000_020L);
        chain.addBlock(branchB2);

        assertArrayEquals(branchB2.header().hash(), chain.head().header().hash());
    }

    @Test
    void uncleAndNephewRewardsAreApplied() {
        Blockchain chain = new Blockchain(ContractCatalog.standardRegistry());
        WorldState genesisState = new WorldState();
        Address minerA = Address.random();
        Address minerB = Address.random();
        Address minerC = Address.random();

        Block genesis = TestSupport.mineBlock(null, genesisState, minerA, List.of(), List.of(), ContractCatalog.standardRegistry(), 1_700_000_000L);
        chain.addGenesis(genesis, genesisState);

        WorldState genesisApplied = chain.stateAt(genesis);
        Block a1 = TestSupport.mineBlock(genesis, genesisApplied, minerA, List.of(), List.of(), ContractCatalog.standardRegistry(), 1_700_000_010L);
        chain.addBlock(a1);

        Block b1 = TestSupport.mineBlock(genesis, genesisApplied, minerB, List.of(), List.of(), ContractCatalog.standardRegistry(), 1_700_000_011L);
        chain.addBlock(b1);

        Block a2 = TestSupport.mineBlock(a1, chain.stateAt(a1), minerA, List.of(), List.of(), ContractCatalog.standardRegistry(), 1_700_000_020L);
        chain.addBlock(a2);

        Block a3 = TestSupport.mineBlock(a2, chain.stateAt(a2), minerC, List.of(), List.of(b1.header()), ContractCatalog.standardRegistry(), 1_700_000_030L);
        chain.addBlock(a3);

        assertEquals(BlockProcessor.UNCLE_REWARD, chain.headState().getOrCreate(minerB).balance());
        assertEquals(BlockProcessor.BASE_BLOCK_REWARD.add(BlockProcessor.NEPHEW_REWARD), chain.headState().getOrCreate(minerC).balance());
    }
}
