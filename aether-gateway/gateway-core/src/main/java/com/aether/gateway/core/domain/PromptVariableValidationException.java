package com.aether.gateway.core.domain;

import java.util.Set;

/** F7.6: a prompt render was rejected because the supplied variables don't exactly match the declared set. */
public class PromptVariableValidationException extends RuntimeException {

    public PromptVariableValidationException(Set<String> missing, Set<String> unexpected) {
        super(buildMessage(missing, unexpected));
    }

    private static String buildMessage(Set<String> missing, Set<String> unexpected) {
        StringBuilder sb = new StringBuilder("Prompt variable validation failed:");
        if (!missing.isEmpty()) {
            sb.append(" missing=").append(missing);
        }
        if (!unexpected.isEmpty()) {
            sb.append(" unexpected=").append(unexpected);
        }
        return sb.toString();
    }
}
