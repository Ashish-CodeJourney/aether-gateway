package com.aether.gateway.router.routing;

/** F9.3: thrown when a configured provider base URL resolves to a private/reserved address. */
public class SsrfProtectionException extends RuntimeException {

    public SsrfProtectionException(String message) {
        super(message);
    }

    public SsrfProtectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
