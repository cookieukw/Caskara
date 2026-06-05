package com.cookie.caskara.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables SQLite FTS5 (Full-Text Search) for this entity.
 * This allows using {@link Query#search(String)} for ultra-fast text matching.
 * <p>
 * WARNING: This annotation is incompatible with @Encrypted.
 * The FTS5 index requires plaintext JSON to build the search dictionary.
 * If both annotations are present, Caskara will throw a DatabaseException.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FullTextSearch {
}
