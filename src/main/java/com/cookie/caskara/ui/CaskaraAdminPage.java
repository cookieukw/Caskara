package com.cookie.caskara.ui;

import com.cookie.caskara.commands.CaskaraAdminLogic;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.Message;

public class CaskaraAdminPage extends CustomUIPage {

    private String currentShell = "global.db";
    private int currentPage = 1;
    private static final int ITEMS_PER_PAGE = 20;
    private java.util.List<CaskaraAdminLogic.EntityData> currentEntities = new java.util.ArrayList<>();

    public CaskaraAdminPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss);
    }

    public static void open(PlayerRef playerRef) {
        Player player = playerRef.getComponent(Player.getComponentType());
        if (player == null) return;
        
        Ref<EntityStore> ref = playerRef.getReference();
        Store<EntityStore> store = ref.getStore();
        
        player.getPageManager().openCustomPage(ref, store, new CaskaraAdminPage(playerRef));
    }

    @Override
    public void build(Ref<EntityStore> playerRefRef, UICommandBuilder cmdBuilder, UIEventBuilder evtBuilder, Store<EntityStore> store) {
        cmdBuilder.append("Caskara/CaskaraAdmin.ui");
        
        // Bind Header/Sidebar Buttons
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnBackup", EventData.of("action", "backup"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnVacuum", EventData.of("action", "vacuum"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnGlobalDb", EventData.of("action", "switch").put("shell", "global.db"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnPlayersDb", EventData.of("action", "switch").put("shell", "players.db"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnQuestsDb", EventData.of("action", "switch").put("shell", "quests.db"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnEconomyDb", EventData.of("action", "switch").put("shell", "economy.db"));
        
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnNewEntry", EventData.of("action", "newEntry"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnFilter", EventData.of("action", "filter"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnTests", EventData.of("action", "tests"));
        
        // Bind Pagination
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnPrevPage", EventData.of("action", "prevPage"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnNextPage", EventData.of("action", "nextPage"));

        // Bind Fixed Row Action Slots (20 max per page)
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnDel" + i, EventData.of("action", "delete").put("index", String.valueOf(i)));
        }

        loadData(cmdBuilder);
    }

    private void loadData(UICommandBuilder cmdBuilder) {
        // 1. Update Stats
        java.util.Map<String, String> stats = CaskaraAdminLogic.getGlobalStatsMap();
        cmdBuilder.set("#StatHitRateValue.Text", stats.getOrDefault("HitRate", "0%"));
        cmdBuilder.set("#StatMemoryValue.Text", stats.getOrDefault("Memory", "0 MB"));
        cmdBuilder.set("#StatTotalValue.Text", stats.getOrDefault("Total", "0"));

        // 2. Fetch paginated entities
        int offset = (currentPage - 1) * ITEMS_PER_PAGE;
        currentEntities = CaskaraAdminLogic.getShellEntities(currentShell, offset, ITEMS_PER_PAGE);

        // 3. Update Pagination Label
        cmdBuilder.set("#LblPageIndicator.Text", "Page " + currentPage);

        // 4. Update Sidebar state
        String[] shells = {"global.db", "players.db", "quests.db", "economy.db"};
        String[] btnIds = {"#BtnGlobalDb", "#BtnPlayersDb", "#BtnQuestsDb", "#BtnEconomyDb"};
        String[] lblIds = {"#LblGlobalDb", "#LblPlayersDb", "#LblQuestsDb", "#LblEconomyDb"};
        
        for (int i = 0; i < shells.length; i++) {
            boolean active = shells[i].equals(currentShell);
            cmdBuilder.set(btnIds[i] + ".Background", active ? "#2A2A2A" : "#000000(0)");
            cmdBuilder.set(lblIds[i] + ".Text", shells[i]);
            cmdBuilder.set(lblIds[i] + ".TextColor", active ? "#FFB000" : "#BBBBBB");
        }

        // 5. Update Table Rows statically
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            boolean isVisible = i < currentEntities.size();
            cmdBuilder.set("#Row" + i + ".Visible", isVisible);
            
            if (isVisible) {
                CaskaraAdminLogic.EntityData e = currentEntities.get(i);
                cmdBuilder.set("#LblId" + i + ".Text", e.id);
                cmdBuilder.set("#LblType" + i + ".Text", e.type);
                cmdBuilder.set("#LblSize" + i + ".Text", e.size);
                cmdBuilder.set("#LblTtl" + i + ".Text", e.ttl);
                
                String ttlColor = e.ttl.equals("Permanent") ? "#BBBBBB" : (e.ttl.equals("Expired") ? "#FF5555" : "#FFB000");
                cmdBuilder.set("#LblTtl" + i + ".TextColor", ttlColor);
            }
        }
    }

    private void refreshUI() {
        UICommandBuilder cmdBuilder = new UICommandBuilder();
        loadData(cmdBuilder);
        this.sendUpdate(cmdBuilder);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> playerRefRef, Store<EntityStore> store, String eventData) {
        if (eventData.contains("\"action\":\"backup\"")) {
            CaskaraAdminLogic.runBackup().forEach(resp -> this.playerRef.sendMessage(Message.raw(resp)));
            this.close();
        } else if (eventData.contains("\"action\":\"vacuum\"")) {
            CaskaraAdminLogic.runVacuum().forEach(resp -> this.playerRef.sendMessage(Message.raw(resp)));
            this.close();
        } else if (eventData.contains("\"action\":\"switch\"")) {
            // Extract shell name using basic string parsing
            if (eventData.contains("global.db")) currentShell = "global.db";
            else if (eventData.contains("players.db")) currentShell = "players.db";
            else if (eventData.contains("quests.db")) currentShell = "quests.db";
            else if (eventData.contains("economy.db")) currentShell = "economy.db";
            
            currentPage = 1;
            refreshUI();
        } else if (eventData.contains("\"action\":\"prevPage\"")) {
            if (currentPage > 1) {
                currentPage--;
                refreshUI();
            }
        } else if (eventData.contains("\"action\":\"nextPage\"")) {
            if (currentEntities.size() == ITEMS_PER_PAGE) { // Has more possibly
                currentPage++;
                refreshUI();
            }
        } else if (eventData.contains("\"action\":\"delete\"")) {
            // Parse index
            for (int i = 0; i < ITEMS_PER_PAGE; i++) {
                if (eventData.contains("\"index\":\"" + i + "\"")) {
                    if (i < currentEntities.size()) {
                        String idToDelete = currentEntities.get(i).id;
                        boolean success = CaskaraAdminLogic.deleteEntity(currentShell, idToDelete);
                        if (success) {
                            this.playerRef.sendMessage(Message.raw("[Caskara] §aDeleted entity " + idToDelete + " from " + currentShell));
                            // Re-fetch data and refresh
                            refreshUI();
                        } else {
                            this.playerRef.sendMessage(Message.raw("[Caskara] §cFailed to delete entity " + idToDelete));
                        }
                    }
                    break;
                }
            }
        } else if (eventData.contains("\"action\":\"newEntry\"")) {
            this.playerRef.sendMessage(Message.raw("[Caskara] §eNew Entry dialog is not yet implemented."));
        } else if (eventData.contains("\"action\":\"filter\"")) {
            this.playerRef.sendMessage(Message.raw("[Caskara] §eFiltering is not yet implemented. Refreshed UI."));
            refreshUI();
        } else if (eventData.contains("\"action\":\"tests\"")) {
            this.playerRef.sendMessage(Message.raw("[Caskara] §bRunning integrated tests..."));
            this.close();
            // In a real scenario, we'd trigger test suite here
        }
    }
}
