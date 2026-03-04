package com.cookie.caskara;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

public class MainPlugin extends JavaPlugin {
    public MainPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        System.out.println("Caskara has been initialized!");
        
        // Initialize Caskara API
        File dataFolder = new File("mods/Caskara/data");
        Caskara.init(dataFolder);

        // Simple Test
        testAPI();
    }

    private void testAPI() {
        // 1. O jeito fácil (Estático e Simples)
        User user1 = new User("Cookie", 25);
        User user2 = new User("Cookie2", 20);
        
        Caskara.save("user_1", user1);
        Caskara.save("user_2", user2);
        
        User carregado = Caskara.load("user_1", User.class);
        System.out.println("[Caskara] Usuário 1 carregado: " + carregado.getName());

        // 2. Exemplo com Dados Complexos (Listas e Objetos)
        var stats = new PlayerStats("CookiePlayer", 10, 100.0);
        stats.addItem("Espada de Madeira");
        stats.addItem("Poção de Cura");
        stats.setLocation(100, 64, -200);

        Caskara.save("stats_cookie", stats);

        var statsCarregados = Caskara.load("stats_cookie", PlayerStats.class);
        System.out.println("[Caskara] Stats com Lista: " + statsCarregados.getInventory());

        // 3. Exemplo de "Lista de Frutas" (Resolvendo a dúvida das Colunas)
        var cesta = new FruitBasket("Minha Cesta");
        cesta.addFruit("Maçã", "Vermelha", 0.2);
        cesta.addFruit("Banana", "Amarela", 0.15);
        
        Caskara.save("cesta_1", cesta);

        var cestaCarregada = Caskara.load("cesta_1", FruitBasket.class);
        System.out.println("[Caskara] Frutas na cesta: " + cestaCarregada.getFruits());

        // 4. Listar todos de um tipo
        var todosUsuarios = Caskara.list(User.class);
        System.out.println("[Caskara] Total de usuários no banco: " + todosUsuarios.size());
    }
}
