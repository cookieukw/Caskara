package com.cookie.caskara.exceptions;

/**
 * Thrown when a database operation fails (e.g., IO error, SQL error).
 */
public class DatabaseException extends CaskaraException {
    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
