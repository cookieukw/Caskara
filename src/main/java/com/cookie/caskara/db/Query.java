package com.cookie.caskara.db;

import com.cookie.caskara.exceptions.DatabaseException;
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

    private Integer limit;
    private Integer offset;
    private String orderByField;
    private Order orderDirection = Order.ASC;

    public enum Order { ASC, DESC }

    public Query(Shell shell, Class<T> clazz, String typeName) {
        this.shell = shell;
        this.clazz = clazz;
        this.typeName = typeName;
    }

    /**
     * Filters by a specific field in the JSON data.
     * Use this with createIndex() for high-performance searches.
     */
    public Query<T> field(String fieldName, Object value) {
        return addFilter("json_extract(json, '$.' || ?) = ?", fieldName, value);
    }

    /**
     * Filters where field is greater than value.
     */
    public Query<T> fieldGreaterThan(String fieldName, Object value) {
        return addFilter("json_extract(json, '$.' || ?) > ?", fieldName, value);
    }

    /**
     * Filters where field is less than value.
     */
    public Query<T> fieldLessThan(String fieldName, Object value) {
        return addFilter("json_extract(json, '$.' || ?) < ?", fieldName, value);
    }

    /**
     * Filters where field matches one of the values in the list.
     */
    public Query<T> fieldIn(String fieldName, List<Object> values) {
        if (values == null || values.isEmpty()) return this;
        StringBuilder inClause = new StringBuilder("json_extract(json, '$.' || ?) IN (");
        params.add(fieldName);
        for (int i = 0; i < values.size(); i++) {
            inClause.append("?");
            if (i < values.size() - 1) inClause.append(",");
            params.add(values.get(i));
        }
        inClause.append(")");
        if (filter.length() > 0) filter.append(" AND ");
        filter.append(inClause);
        return this;
    }

    /**
     * Filters where a JSON field contains a specific string (SQL LIKE).
     */
    public Query<T> fieldContains(String fieldName, String value) {
        return addFilter("json_extract(json, '$.' || ?) LIKE ?", fieldName, "%" + value + "%");
    }

    private Query<T> addFilter(String clause, Object... values) {
        if (filter.length() > 0) filter.append(" AND ");
        filter.append(clause);
        for (Object val : values) {
            params.add(val);
        }
        return this;
    }

    /**
     * Limits the number of results returned.
     */
    public Query<T> limit(int limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Skips a number of results.
     */
    public Query<T> offset(int offset) {
        this.offset = offset;
        return this;
    }

    /**
     * Helper for pagination. Sets both limit and offset.
     * page(1, 50) -> limit(50), offset(0)
     */
    public Query<T> page(int page, int size) {
        this.limit = size;
        this.offset = (page - 1) * size;
        return this;
    }

    /**
     * Orders the results by a JSON field.
     */
    public Query<T> orderBy(String fieldName, Order direction) {
        this.orderByField = fieldName;
        this.orderDirection = direction;
        return this;
    }

    /**
     * Executes the query and returns a list of results.
     */
    public List<T> fetch() {
        long startTime = System.nanoTime();
        try {
            return shell.runInLock(() -> {
                List<T> results = new ArrayList<>();
                StringBuilder sql = new StringBuilder(
                    "SELECT json FROM elements WHERE type = ? AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)");

                if (filter.length() > 0) {
                    sql.append(" AND ").append(filter);
                }
                if (orderByField != null) {
                    sql.append(" ORDER BY json_extract(json, '$.' || ?) ").append(orderDirection.name());
                }
                if (limit != null) {
                    sql.append(" LIMIT ").append(limit);
                }
                if (offset != null) {
                    sql.append(" OFFSET ").append(offset);
                }

                try (PreparedStatement pstmt = shell.getConnection().prepareStatement(sql.toString())) {
                    int paramIndex = 1;
                    pstmt.setString(paramIndex++, typeName);
                    pstmt.setLong(paramIndex++, System.currentTimeMillis());

                    for (Object param : params) {
                        pstmt.setObject(paramIndex++, param);
                    }
                    if (orderByField != null) {
                        pstmt.setString(paramIndex++, orderByField);
                    }

                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            results.add(Core.getGson().fromJson(rs.getString("json"), clazz));
                        }
                    }
                } catch (SQLException e) {
                    throw new DatabaseException("Failed to fetch query results", e);
                }
                return results;
            });
        } finally {
            shell.getStats().recordQuery(System.nanoTime() - startTime);
        }
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
