package com.metaform.cxve.adapter.out.vault;

/**
 * A Vault operation failed (login, read or write). Deliberately unchecked and allowed to reach
 * the web layer as a 500 on the registration paths: a provider whose callback could not be
 * stored must learn so, not receive a 204 for a registration that evaporated.
 */
public class VaultAccessException extends RuntimeException {

    public VaultAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
