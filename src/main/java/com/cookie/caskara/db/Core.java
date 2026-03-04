package com.cookie.caskara.db;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A 'Core' represents a collection of a specific type within a Shell.
 * It provides the classic CRUD operations with a thematic spin.
 */
public class Core<T> {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private final Shell shell;
    private final Class<T> clazz;
    private final String typeName;

    public Core(Shell shell, Class<T> clazz) {
        this.shell = shell;
        this.clazz = clazz;
        this.typeName = clazz.getSimpleName().toLowerCase();
    }

    /**
     * Preserves an element in the shell.
     */
    public void preserve(String id, T element) {
        String json = GSON.toJson(element);
        String sql = "INSERT OR REPLACE INTO elements (id, type, json) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, typeName);
            pstmt.setString(3, json);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public CompletableFuture<Void> preserveAsync(String id, T element) {
        return CompletableFuture.runAsync(() -> preserve(id, element), shell.getExecutor());
    }

    /**
     * Extracts a 'Pearl' (result) from the shell by ID.
     */
    public Pearl<T> extract(String id) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT json FROM elements WHERE id = ? AND type = ?";
            try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, id);
                pstmt.setString(2, typeName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return GSON.fromJson(rs.getString("json"), clazz);
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return null;
        }, shell.getExecutor());
        return new Pearl<>(future);
    }

    /**
     * Discards an element from the shell.
     */
    public void discard(String id) {
        String sql = "DELETE FROM elements WHERE id = ? AND type = ?";
        try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, typeName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Extracts all elements of this type.
     */
    public List<T> extractAll() {
        List<T> results = new ArrayList<>();
        String sql = "SELECT json FROM elements WHERE type = ?";
        try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, typeName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results.add(GSON.fromJson(rs.getString("json"), clazz));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return results;
    }

    /**
     * Starts a new fluent query.
     */
    public Query<T> query() {
        return new Query<>(shell, clazz, typeName);
    }

    // Pass Gson down to Query if needed
    static Gson getGson() {
        return GSON;
    }
}
