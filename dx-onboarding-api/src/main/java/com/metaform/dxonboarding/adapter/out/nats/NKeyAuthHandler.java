package com.metaform.dxonboarding.adapter.out.nats;

import io.nats.client.AuthHandler;
import io.nats.client.NKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Authenticates with the ed25519 NKey seed the Vault init container wrote; the seed never leaves
 * the process — the server sends a nonce, the handler returns its signature.
 */
public class NKeyAuthHandler implements AuthHandler {

    private final char[] seed;

    public NKeyAuthHandler(Path seedFile) {
        try {
            this.seed = new String(Files.readAllBytes(seedFile), StandardCharsets.UTF_8).trim().toCharArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the NATS NKey seed from " + seedFile, e);
        }
    }

    @Override
    public char[] getID() {
        try {
            return NKey.fromSeed(seed).getPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive the public key from the NKey seed", e);
        }
    }

    @Override
    public byte[] sign(byte[] nonce) {
        try {
            return NKey.fromSeed(seed).sign(nonce);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign the NATS nonce with the NKey seed", e);
        }
    }

    @Override
    public char[] getJWT() {
        // NKey auth, not JWT/creds-based
        return null;
    }
}
