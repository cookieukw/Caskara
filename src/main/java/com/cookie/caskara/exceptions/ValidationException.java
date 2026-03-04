package com.cookie.caskara.exceptions;

/**
 * Thrown when data validation fails.
 */
public class ValidationException extends CaskaraException {
    public ValidationException(String message) {
        super(message);
    }
}
