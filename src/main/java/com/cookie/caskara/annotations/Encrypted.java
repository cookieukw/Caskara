package com.cookie.caskara.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks this entity to enforce AES-256 encryption.
 * The security key must be provided at runtime via Caskara.encrypt() before saving or loading data.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Encrypted {
}
