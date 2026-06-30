package dev.naoki.ethwhite.ponzi;

/**
 * Event types emitted by the Ponzi contract.
 * Used to annotate fund-flow events so downstream analyzers
 * can distinguish stakes, withdrawals, referral rewards, and dividends.
 */
public enum EventType {
    /** Investor deposits ETH into the contract (stake). */
    STAKE,

    /** Investor withdraws their available balance. */
    WITHDRAW,

    /** Contract pays a referral bonus to an upline investor. */
    REFERRAL_REWARD,

    /** Contract pays a dividend share to an existing investor from the investor pool. */
    DIVIDEND,

    /** Fallback: generic transfer whose business meaning is unknown. */
    TRANSFER
}
