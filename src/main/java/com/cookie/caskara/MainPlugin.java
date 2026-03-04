package com.cookie.caskara;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.io.File;
import java.util.List;

public class MainPlugin extends JavaPlugin {
    public MainPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        System.out.println("Caskara Advanced Data Engine initialized!");
        
        File folder = new File("mods/Caskara/data");
        Caskara.init(folder);

        testAdvancedFeatures();
    }

    private void testAdvancedFeatures() {
        // 1. Reactive Real-time Monitoring
        Caskara.core(PlayerData.class).observeAll((id, data) -> {
            System.out.println("[Caskara Observer] Data changed for " + id + ": " + data.getName());
        });

        // 2. Premium Security (AES-256)
        Caskara.encrypt(PlayerData.class, "hytale-secure-key-123");

        // 3. ACID Transactions (Batch Operations)
        Caskara.transaction(tx -> {
            PlayerData p1 = new PlayerData("Cookie", 1000);
            PlayerData p2 = new PlayerData("Antigravity", 500);
            
            tx.save("player_1", p1);
            tx.save("player_2", p2);
            
            System.out.println("[Caskara] Transaction: Atomically saved two players.");
        });

        // 4. Advanced Engine: paginated query with operators
        List<PlayerData> richPlayers = Caskara.query(PlayerData.class)
                .fieldGreaterThan("balance", 100)
                .fieldContains("name", "o")
                .orderBy("balance", com.cookie.caskara.db.Query.Order.DESC)
                .page(1, 10)
                .fetch();

        System.out.println("[Caskara] Query Results: Found " + richPlayers.size() + " wealthy players.");

        // 5. Technical Observability
        var stats = Caskara.stats();
        System.out.println("--- Caskara Metrics ---");
        System.out.println("Cache Hit Rate: " + (stats.getCacheHitRate() * 100) + "%");
        System.out.println("Avg Query Latency: " + stats.getAverageQueryTimeMs() + "ms");
    }

    public static class PlayerData {
        private String id;
        private String name;
        private int balance;

        public PlayerData() {}
        public PlayerData(String name, int balance) {
            this.name = name;
            this.balance = balance;
        }
        public String getName() { return name; }
    }
}
