package dev.naoki.ethwhite.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public final class CallData {
    private final String method;
    private final Map<String, String> args;

    private CallData(String method, Map<String, String> args) {
        this.method = Objects.requireNonNull(method, "method");
        this.args = Map.copyOf(args);
    }

    public static Builder builder(String method) {
        return new Builder(method);
    }

    public static CallData parse(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        String[] parts = text.split(";", -1);
        Map<String, String> args = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isBlank()) {
                continue;
            }
            int idx = parts[i].indexOf('=');
            if (idx <= 0) {
                throw new IllegalArgumentException("Invalid calldata fragment: " + parts[i]);
            }
            args.put(parts[i].substring(0, idx), parts[i].substring(idx + 1));
        }
        return new CallData(parts[0], args);
    }

    public String method() {
        return method;
    }

    public String arg(String key) {
        String value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing argument: " + key);
        }
        return value;
    }

    public String argOrDefault(String key, String defaultValue) {
        return args.getOrDefault(key, defaultValue);
    }

    public List<String> list(String key) {
        String value = argOrDefault(key, "");
        if (value.isBlank()) {
            return List.of();
        }
        return Arrays.asList(value.split(","));
    }

    public byte[] encode() {
        StringJoiner joiner = new StringJoiner(";");
        joiner.add(method);
        args.forEach((key, value) -> joiner.add(key + "=" + value));
        return joiner.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static final class Builder {
        private final String method;
        private final Map<String, String> args = new LinkedHashMap<>();

        private Builder(String method) {
            this.method = method;
        }

        public Builder put(String key, String value) {
            args.put(key, value);
            return this;
        }

        public Builder put(String key, Address value) {
            return put(key, value.toHex());
        }

        public Builder put(String key, long value) {
            return put(key, Long.toString(value));
        }

        public Builder put(String key, java.math.BigInteger value) {
            return put(key, value.toString());
        }

        public Builder putList(String key, List<String> values) {
            args.put(key, String.join(",", values));
            return this;
        }

        public CallData build() {
            return new CallData(method, args);
        }
    }
}
