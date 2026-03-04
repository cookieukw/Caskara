package com.cookie.caskara.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A fluent query builder for Caskara.
 */
public class Query<T> {
    private final Shell shell;
    private final Class<T> clazz;
    private final String typeName;
    private final StringBuilder filter = new StringBuilder();
    private final List<Object> params = new ArrayList<>();

    public Query(Shell shell, Class<T> clazz, String typeName) {
        this.shell = shell;
        this.clazz = clazz;
        this.typeName = typeName;
    }

    /**
     * Filters by a specific field in the JSON data.
     */
    public Query<T> field(String fieldName, Object value) {
        if (filter.length() > 0) filter.append(" AND ");
        filter.append("json_extract(json, '$.' || ?) = ?");
        params.add(fieldName);
        params.add(value);
        return this;
    }

    /**
     * Executes the query and returns a list of results.
     */
    public List<T> fetch() {
        List<T> results = new ArrayList<>();
        String sql = "SELECT json FROM elements WHERE type = ?";
        if (filter.length() > 0) {
            sql += " AND " + filter.toString();
        }

        try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql)) {
            pstmt.setString(1, typeName);
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 2, params.get(i));
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results.add(Core.getGson().fromJson(rs.getString("json"), clazz));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return results;
    }

    /**
     * Executes the query asynchronously.
     */
    public CompletableFuture<List<T>> fetchAsync() {
        return CompletableFuture.supplyAsync(this::fetch, shell.getExecutor());
    }

    /**
     * Fetches only the first result found.
     */
    public Pearl<T> fetchFirst() {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            List<T> results = fetch();
            return results.isEmpty() ? null : results.get(0);
        }, shell.getExecutor());
        return new Pearl<>(future);
    }
}
