package com.cookie.caskara.db;

import com.cookie.caskara.exceptions.DatabaseException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A fluent query builder for Caskara.
 */
public class Query<T> {
    private final Core<T> core;
    private final Shell shell;
    private final Class<T> clazz;
    private final String typeName;
    
    private final StringBuilder sqlFilter = new StringBuilder();
    private final List<Object> sqlParams = new ArrayList<>();
    private final List<Condition> conditions = new ArrayList<>();

    private Integer limit;
    private Integer offset;
    private String orderByField;
    private Order orderDirection = Order.ASC;

    public enum Order { ASC, DESC }

    private static class Condition {
        String fieldName;
        String operator;
        Object value;

        Condition(String fieldName, String operator, Object value) {
            this.fieldName = fieldName;
            this.operator = operator;
            this.value = value;
        }
    }

    public Query(Core<T> core, Shell shell, Class<T> clazz, String typeName) {
        this.core = core;
        this.shell = shell;
        this.clazz = clazz;
        this.typeName = typeName;
    }

    /**
     * Filters by a specific field in the JSON data.
     */
    public Query<T> field(String fieldName, Object value) {
        conditions.add(new Condition(fieldName, "=", value));
        return addSqlFilter("json_extract(json, '$.' || ?) = ?", fieldName, value);
    }

    /**
     * Filters where field is greater than value.
     */
    public Query<T> fieldGreaterThan(String fieldName, Object value) {
        conditions.add(new Condition(fieldName, ">", value));
        return addSqlFilter("json_extract(json, '$.' || ?) > ?", fieldName, value);
    }

    /**
     * Filters where field is less than value.
     */
    public Query<T> fieldLessThan(String fieldName, Object value) {
        conditions.add(new Condition(fieldName, "<", value));
        return addSqlFilter("json_extract(json, '$.' || ?) < ?", fieldName, value);
    }

    /**
     * Filters where field matches one of the values in the list.
     */
    @SuppressWarnings("unchecked")
    public Query<T> fieldIn(String fieldName, List<Object> values) {
        if (values == null || values.isEmpty()) return this;
        conditions.add(new Condition(fieldName, "IN", values));
        
        StringBuilder inClause = new StringBuilder("json_extract(json, '$.' || ?) IN (");
        sqlParams.add(fieldName);
        for (int i = 0; i < values.size(); i++) {
            inClause.append("?");
            if (i < values.size() - 1) inClause.append(",");
            sqlParams.add(values.get(i));
        }
        inClause.append(")");
        if (sqlFilter.length() > 0) sqlFilter.append(" AND ");
        sqlFilter.append(inClause);
        return this;
    }

    /**
     * Filters where a JSON field contains a specific string (SQL LIKE).
     */
    public Query<T> fieldContains(String fieldName, String value) {
        conditions.add(new Condition(fieldName, "CONTAINS", value));
        return addSqlFilter("json_extract(json, '$.' || ?) LIKE ?", fieldName, "%" + value + "%");
    }

    private Query<T> addSqlFilter(String clause, Object... values) {
        if (sqlFilter.length() > 0) sqlFilter.append(" AND ");
        sqlFilter.append(clause);
        for (Object val : values) {
            sqlParams.add(val);
        }
        return this;
    }

    public Query<T> limit(int limit) {
        this.limit = limit;
        return this;
    }

    public Query<T> offset(int offset) {
        this.offset = offset;
        return this;
    }

    public Query<T> page(int page, int size) {
        this.limit = size;
        this.offset = (page - 1) * size;
        return this;
    }

    public Query<T> orderBy(String fieldName, Order direction) {
        this.orderByField = fieldName;
        this.orderDirection = direction;
        return this;
    }

    /**
     * Executes the query and returns a list of results.
     * Automatically chooses between SQL JSON execution and in-memory fallback for encrypted data.
     */
    public List<T> fetch() {
        if (core.isEncrypted()) {
            return fetchFromMemory();
        }

        long startTime = System.nanoTime();
        try {
            return shell.runInLock(() -> {
                List<T> results = new ArrayList<>();
                StringBuilder sql = new StringBuilder(
                    "SELECT json FROM elements WHERE type = ? AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)");

                if (sqlFilter.length() > 0) {
                    sql.append(" AND ").append(sqlFilter);
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

                    for (Object param : sqlParams) {
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
     * In-memory fallback for encrypted cores where SQL JSON functions fail.
     */
    private List<T> fetchFromMemory() {
        long startTime = System.nanoTime();
        try {
            // 1. Fetch all records for this type (they get decrypted by Core.extractAll)
            List<T> all = core.extractAll();
            Stream<T> stream = all.stream();

            // 2. Filter
            for (Condition cond : conditions) {
                stream = stream.filter(item -> match(item, cond));
            }

            // 3. Sort
            if (orderByField != null) {
                Comparator<T> comparator = (a, b) -> {
                    Comparable valA = getFieldValue(a, orderByField);
                    Comparable valB = getFieldValue(b, orderByField);
                    if (valA == null || valB == null) return 0;
                    return orderDirection == Order.ASC ? valA.compareTo(valB) : valB.compareTo(valA);
                };
                stream = stream.sorted(comparator);
            }

            // 4. Pagination
            if (offset != null) {
                stream = stream.skip(offset);
            }
            if (limit != null) {
                stream = stream.limit(limit);
            }

            return stream.collect(Collectors.toList());
        } finally {
            shell.getStats().recordQuery(System.nanoTime() - startTime);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean match(T item, Condition cond) {
        Comparable actual = getFieldValue(item, cond.fieldName);
        if (actual == null) return false;

        Object target = cond.value;
        switch (cond.operator) {
            case "=":
                return actual.equals(target);
            case ">":
                return compare(actual, target) > 0;
            case "<":
                return compare(actual, target) < 0;
            case "CONTAINS":
                return actual.toString().toLowerCase().contains(target.toString().toLowerCase());
            case "IN":
                List<Object> values = (List<Object>) target;
                return values.stream().anyMatch(v -> actual.equals(v));
            default:
                return false;
        }
    }

    @SuppressWarnings("unchecked")
    private int compare(Comparable actual, Object target) {
        if (target instanceof Number && actual instanceof Number) {
            return Double.compare(((Number) actual).doubleValue(), ((Number) target).doubleValue());
        }
        if (target instanceof Comparable) {
             return actual.compareTo(target);
        }
        return actual.compareTo(target.toString());
    }

    private Comparable getFieldValue(T item, String fieldName) {
        try {
            // Convert to JsonObject to easily extract any field
            JsonObject json = Core.getGson().toJsonTree(item).getAsJsonObject();
            JsonElement element = json.get(fieldName);
            if (element == null || element.isJsonNull()) return null;
            
            if (element.isJsonPrimitive()) {
                var prim = element.getAsJsonPrimitive();
                if (prim.isNumber()) return prim.getAsNumber().doubleValue();
                if (prim.isBoolean()) return prim.getAsBoolean();
                return prim.getAsString();
            }
            return element.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public CompletableFuture<List<T>> fetchAsync() {
        return CompletableFuture.supplyAsync(this::fetch, shell.getExecutor());
    }

    public Pearl<T> fetchFirst() {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            List<T> results = fetch();
            return results.isEmpty() ? null : results.get(0);
        }, shell.getExecutor());
        return new Pearl<>(future);
    }
}
