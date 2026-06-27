package dev.naoki.ethwhite.core;

import dev.naoki.ethwhite.crypto.Keccak;
import dev.naoki.ethwhite.ponzi.FundFlowEvent;
import dev.naoki.ethwhite.util.Bytes;
import dev.naoki.ethwhite.util.PatriciaTrie;
import dev.naoki.ethwhite.util.Rlp;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class WorldState {
    private final NavigableMap<Address, Account> accounts;
    private TransferCapture transferCapture;

    public WorldState() {
        this.accounts = new TreeMap<>();
    }

    private WorldState(NavigableMap<Address, Account> accounts) {
        this.accounts = accounts;
    }

    public WorldState copy() {
        NavigableMap<Address, Account> cloned = new TreeMap<>();
        accounts.forEach((address, account) -> cloned.put(address, account.copy()));
        return new WorldState(cloned);
    }

    public Account get(Address address) {
        return accounts.get(address);
    }

    public Account getOrCreate(Address address) {
        return accounts.computeIfAbsent(address, ignored -> new Account());
    }

    public boolean exists(Address address) {
        return accounts.containsKey(address);
    }

    public Collection<Map.Entry<Address, Account>> entries() {
        return accounts.entrySet();
    }

    public void restore(WorldState other) {
        accounts.clear();
        other.accounts.forEach((address, account) -> accounts.put(address, account.copy()));
    }

    public void transfer(Address from, Address to, BigInteger amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Transfer amount must not be negative");
        }
        if (amount.signum() == 0) {
            return;
        }
        getOrCreate(from).debit(amount);
        getOrCreate(to).credit(amount);
        if (transferCapture != null) {
            transferCapture.record(from, to, amount);
        }
    }

    public void beginTransferCapture(BlockContext blockContext, byte[] txHash) {
        transferCapture = new TransferCapture(blockContext, txHash);
    }

    public List<FundFlowEvent> capturedTransfers() {
        if (transferCapture == null) {
            return List.of();
        }
        return transferCapture.events();
    }

    public void clearTransferCapture() {
        transferCapture = null;
    }

    public byte[] stateRoot() {
        var entries = new ArrayList<PatriciaTrie.Entry>();
        for (Map.Entry<Address, Account> entry : accounts.entrySet()) {
            entries.add(new PatriciaTrie.Entry(
                    Keccak.hash(entry.getKey().toBytes()),
                    accountValue(entry.getValue())
            ));
        }
        return PatriciaTrie.root(entries);
    }

    public String describeBalance(Address address) {
        Account account = accounts.get(address);
        return account == null ? "0" : account.balance().toString();
    }

    private byte[] accountValue(Account account) {
        return Rlp.encodeList(
                Rlp.encodeLong(account.nonce()),
                Rlp.encodeBigInteger(account.balance()),
                Rlp.encodeBytes(storageRoot(account)),
                Rlp.encodeBytes(codeHash(account))
        );
    }

    private byte[] storageRoot(Account account) {
        var entries = new ArrayList<PatriciaTrie.Entry>();
        for (Map.Entry<BigInteger, Word> storageEntry : account.storage().entrySet()) {
            entries.add(new PatriciaTrie.Entry(
                    Keccak.hash(Bytes.leftPad(Bytes.ofBigInteger(storageEntry.getKey()), Word.SIZE)),
                    Rlp.encodeScalarBytes(storageEntry.getValue().toBytes())
            ));
        }
        for (Map.Entry<String, byte[]> metadataEntry : account.metadata().entrySet()) {
            entries.add(new PatriciaTrie.Entry(
                    Keccak.hash(("meta:" + metadataEntry.getKey()).getBytes(StandardCharsets.UTF_8)),
                    Rlp.encodeBytes(metadataEntry.getValue())
            ));
        }
        return PatriciaTrie.root(entries);
    }

    private byte[] codeHash(Account account) {
        if (account.contractId() != null) {
            return Keccak.hash(("native:" + account.contractId()).getBytes(StandardCharsets.UTF_8));
        }
        return Keccak.hash(account.code());
    }

    private static final class TransferCapture {
        private final BlockContext blockContext;
        private final byte[] txHash;
        private final List<FundFlowEvent> events = new ArrayList<>();

        private TransferCapture(BlockContext blockContext, byte[] txHash) {
            this.blockContext = blockContext;
            this.txHash = txHash.clone();
        }

        private void record(Address from, Address to, BigInteger amount) {
            events.add(new FundFlowEvent(
                    from,
                    to,
                    amount,
                    blockContext.number(),
                    txHash,
                    blockContext.timestamp()
            ));
        }

        private List<FundFlowEvent> events() {
            return List.copyOf(events);
        }
    }
}
