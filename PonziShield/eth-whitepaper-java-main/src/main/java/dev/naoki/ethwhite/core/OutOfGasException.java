package dev.naoki.ethwhite.core;

public final class OutOfGasException extends RuntimeException {
    public OutOfGasException(String message) {
        super(message);
    }
}
