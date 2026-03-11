package com.cookie.caskara;

import com.cookie.caskara.db.Core;
import com.cookie.caskara.db.Shell;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Query builder: filtering, ordering, and pagination.
 */
class QueryTest {

    private Shell shell;
    private Core<Item> core;

    @BeforeEach
    void setup() {
        shell = new Shell(createTempDb());
        core = shell.core(Item.class);

        // Seed data
        core.preserve("a", new Item("Sword", 100, "weapon"));
        core.preserve("b", new Item("Shield", 200, "armor"));
        core.preserve("c", new Item("Bow", 50, "weapon"));
        core.preserve("d", new Item("Helmet", 150, "armor"));
        core.preserve("e", new Item("Arrow", 10, "weapon"));
    }

    @AfterEach
    void teardown() {
        shell.close();
    }

    @Test
    @DisplayName("field() filters by exact JSON field value")
    void testFieldFilter() {
        List<Item> weapons = core.query().field("type", "weapon").fetch();
        assertEquals(3, weapons.size());
        assertTrue(weapons.stream().allMatch(i -> "weapon".equals(i.type)));
    }

    @Test
    @DisplayName("fieldGreaterThan() returns items where field > value")
    void testFieldGreaterThan() {
        List<Item> expensive = core.query().fieldGreaterThan("price", 100).fetch();
        assertEquals(2, expensive.size());
        assertTrue(expensive.stream().allMatch(i -> i.price > 100));
    }

    @Test
    @DisplayName("fieldLessThan() returns items where field < value")
    void testFieldLessThan() {
        List<Item> cheap = core.query().fieldLessThan("price", 100).fetch();
        assertEquals(2, cheap.size());
        assertTrue(cheap.stream().allMatch(i -> i.price < 100));
    }

    @Test
    @DisplayName("fieldContains() does a LIKE search on JSON fields")
    void testFieldContains() {
        List<Item> results = core.query().fieldContains("name", "ow").fetch();
        // "Bow" and "Arrow" both contain "ow"
        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("fieldIn() matches any value in the list")
    void testFieldIn() {
        List<Item> results = core.query()
                .fieldIn("name", List.of("Sword", "Helmet"))
                .fetch();
        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("orderBy() ASC sorts results in ascending order")
    void testOrderByAsc() {
        List<Item> results = core.query()
                .orderBy("price", com.cookie.caskara.db.Query.Order.ASC)
                .fetch();
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).price <= results.get(i).price);
        }
    }

    @Test
    @DisplayName("orderBy() DESC sorts results in descending order")
    void testOrderByDesc() {
        List<Item> results = core.query()
                .orderBy("price", com.cookie.caskara.db.Query.Order.DESC)
                .fetch();
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).price >= results.get(i).price);
        }
    }

    @Test
    @DisplayName("page(1, 2) returns first 2 results")
    void testPaginationFirstPage() {
        List<Item> page = core.query()
                .orderBy("price", com.cookie.caskara.db.Query.Order.ASC)
                .page(1, 2)
                .fetch();
        assertEquals(2, page.size());
    }

    @Test
    @DisplayName("page(2, 2) returns the second page of results")
    void testPaginationSecondPage() {
        List<Item> page1 = core.query()
                .orderBy("price", com.cookie.caskara.db.Query.Order.ASC)
                .page(1, 2)
                .fetch();
        List<Item> page2 = core.query()
                .orderBy("price", com.cookie.caskara.db.Query.Order.ASC)
                .page(2, 2)
                .fetch();
        assertNotEquals(page1.get(0).name, page2.get(0).name);
    }

    @Test
    @DisplayName("fetchFirst() returns a Pearl with the first result")
    void testFetchFirst() {
        var result = core.query()
                .orderBy("price", com.cookie.caskara.db.Query.Order.ASC)
                .fetchFirst();
        Item first = result.sync().orElse(null);
        assertNotNull(first);
        assertEquals("Arrow", first.name); // cheapest item
    }

    @Test
    @DisplayName("Combined filter + orderBy + page works correctly")
    void testChainedQuery() {
        List<Item> results = core.query()
                .field("type", "weapon")
                .orderBy("price", com.cookie.caskara.db.Query.Order.DESC)
                .page(1, 2)
                .fetch();
        assertEquals(2, results.size());
        assertEquals("Sword", results.get(0).name); // 100 > 50 > 10
    }

    // --- Entity ---
    static class Item {
        public String id;
        public String name;
        public int price;
        public String type;
        public Item() {}
        public Item(String name, int price, String type) {
            this.name = name;
            this.price = price;
            this.type = type;
        }
    }

    static java.io.File createTempDb() {
        try {
            java.io.File f = java.io.File.createTempFile("caskara-test-", ".db");
            f.deleteOnExit();
            return f;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
