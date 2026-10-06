package com.metaform.cxve.adapter.out.nats;

import io.nats.client.AuthHandler;
import io.nats.client.NKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NATS {@link AuthHandler} that authenticates with an ed25519 NKey seed read from a file (the
 * Vault-mounted {@code /vault/secrets/nats.nk}). The seed never leaves the process: the server sends
 * a nonce and we return its signature.
 *
 * <p>The seed is held in memory as a char array for the lifetime of the connection, matching how the
 * platform's other Java runtimes authenticate.
 */
public class NKeyAuthHandler implements AuthHandler {

    private final char[] seed;

    public NKeyAuthHandler(Path seedFile) {
        try {
            this.seed = new String(Files.readAllBytes(seedFile), StandardCharsets.UTF_8).trim().toCharArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read NATS NKey seed from " + seedFile, e);
        }
    }

    @Override
    public char[] getID() {
        try {
            return NKey.fromSeed(seed).getPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive public key from NKey seed", e);
        }
    }

    @Override
    public byte[] sign(byte[] nonce) {
        try {
            return NKey.fromSeed(seed).sign(nonce);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign NATS nonce with NKey seed", e);
        }
    }

    @Override
    public char[] getJWT() {
        // NKey auth (not JWT/creds-based), so no JWT is presented.
        return null;
    }
}
