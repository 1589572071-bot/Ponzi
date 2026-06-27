# Ethereum Whitepaper Java

`eth-whitepaper-java` is an educational Java reference implementation inspired by the 2014 Ethereum whitepaper by Vitalik Buterin.

It is not a production Ethereum client. The goal is clarity over completeness: the codebase is compact, readable, and focused on the major execution and state-transition ideas described in the whitepaper.

## What It Includes

- Account-based state
- Signed transactions with nonce protection
- Gas accounting, refunds, and revert behavior
- Contract creation and contract-to-contract message execution
- A small EVM-like bytecode interpreter
- Block processing, proof-of-work checks, uncle rewards, and GHOST-style chain selection
- Whitepaper-inspired example applications implemented as native Java contracts

## Core Components

### Protocol

- Accounts with nonce, balance, code, and storage
- Transaction validation with `APPLY(S, TX) -> S'` style transitions
- Upfront gas charging, execution metering, refunds, and rollback on failure
- Contract deployment for:
  - native Java contracts
  - bytecode contracts executed by the mini VM
- Bytecode VM with stack, memory, storage, jumps, calldata access, and return values
- Block headers, transaction roots, uncle roots, PoW validation, miner rewards, uncle rewards, and chain-head selection by observed subtree weight

### Example Contracts

- `token`: token balances and transfers
- `priceFeed`: trusted oracle updates
- `hedge`: whitepaper-style derivative or stable-value hedge logic
- `nameRegistry`: name registration, update, and transfer
- `fileStorage`: Merkle-proof-based storage challenge logic
- `dao`: proposal, voting, and membership mutation logic

## Project Structure

```text
src/main/java/dev/naoki/ethwhite/
  core/       protocol state, transactions, gas, blocks, blockchain
  crypto/     keccak and secp256k1 helpers
  contract/   native contract execution interfaces
  vm/         small EVM-like interpreter
  sample/     whitepaper-style application contracts
  util/       byte, hex, trie, RLP, and Merkle helpers
```

## Build and Test

Requirements:

- Java 21+
- Maven 3.9+

Run tests:

```bash
mvn test
```

Build the jar:

```bash
mvn package
```

Run the demo entrypoint:

```bash
mvn -q -DskipTests exec:java -Dexec.mainClass=dev.naoki.ethwhite.Main
```

You can also run `dev.naoki.ethwhite.Main` directly from your IDE.

## Test Coverage

The tests cover:

- Ether transfers and fee accounting
- VM storage, write, and return behavior
- Revert-on-failure and gas burning
- Token, hedge, DAO, and file-storage scenarios
- GHOST head selection and uncle or nephew rewards

## Design Notes

This project tracks the whitepaper conceptually, while intentionally keeping some parts compact and educational.

- It uses a simplified deterministic state root instead of a full production Merkle Patricia Trie implementation.
- The VM is intentionally small and not opcode-complete.
- The application contracts are written as native Java contracts for readability.
- Networking, peer discovery, and production consensus hardening are out of scope.

That tradeoff keeps the codebase small enough to audit, study, and extend.
