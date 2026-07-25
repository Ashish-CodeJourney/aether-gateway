package com.aether.gateway.core.domain;

/** F7.2: the X-Aether-Prompt header referenced a prompt/alias/version that does not exist. */
public class PromptNotFoundException extends RuntimeException {

    public PromptNotFoundException(String reference) {
        super("Prompt reference not found: " + reference);
    }
}
