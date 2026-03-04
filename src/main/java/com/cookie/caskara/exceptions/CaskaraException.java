package com.cookie.caskara.exceptions;

/**
 * Base exception for all Caskara-related errors.
 */
public class CaskaraException extends RuntimeException {
    public CaskaraException(String message) {
        super(message);
    }

    public CaskaraException(String message, Throwable cause) {
        super(message, cause);
    }
}
