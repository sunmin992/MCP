package com.wastesim.readiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** A receipt is valid only for its exact inputs and provider data. No stale success is reused. */
public final class ValidationReceipt {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String fingerprint;
    private final Supplier<String> current;
    private final ObjectNode report;
    private final Instant checkedAt = Instant.now();

    public ValidationReceipt(ObjectNode report, Supplier<String> current) {
        this(report, current.get(), current);
    }
    public ValidationReceipt(ObjectNode report, String fingerprint, Supplier<String> current) {
        this.current = current;
        this.fingerprint = fingerprint;
        this.report = report.deepCopy();
    }
    public boolean current() {
        try { return fingerprint.equals(current.get()); }
        catch (RuntimeException e) { return false; }
    }
    public ObjectNode view() {
        ObjectNode out = report.deepCopy();
        out.put("fingerprint", fingerprint);
        out.put("validatedAt", checkedAt.toString());
        out.put("current", current());
        return out;
    }
    public static String fingerprint(JsonNode node) {
        try { return digest(JSON.writeValueAsBytes(sorted(node))); }
        catch (java.io.IOException e) { throw new IllegalArgumentException(e); }
    }
    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(k -> result.set(k, sorted(node.get(k)))); return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode(); node.forEach(n -> result.add(sorted(n))); return result;
        }
        return node;
    }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
