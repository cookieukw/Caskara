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
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.Message;

public class CaskaraAdminPage extends CustomUIPage {

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
        // Load the UI markup into the page
        cmdBuilder.append("Caskara/CaskaraAdmin.ui");
        
        
        // Stats update will be implemented when we assign IDs to Labels

        // Bind Buttons
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnBackup", com.hypixel.hytale.server.core.ui.builder.EventData.of("action", "backup"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnVacuum", com.hypixel.hytale.server.core.ui.builder.EventData.of("action", "vacuum"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnGlobalDb", com.hypixel.hytale.server.core.ui.builder.EventData.of("action", "globaldb"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnPlayersDb", com.hypixel.hytale.server.core.ui.builder.EventData.of("action", "playersdb"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnQuestsDb", com.hypixel.hytale.server.core.ui.builder.EventData.of("action", "questsdb"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnEconomyDb", com.hypixel.hytale.server.core.ui.builder.EventData.of("action", "economydb"));
        evtBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#BtnTests", com.hypixel.hytale.server.core.ui.builder.EventData.of("action", "tests"));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> playerRefRef, Store<EntityStore> store, String eventData) {
        if (eventData.contains("\"action\":\"backup\"")) {
            java.util.List<String> responses = CaskaraAdminLogic.runBackup();
            for (String response : responses) {
                this.playerRef.sendMessage(Message.raw(response));
            }
            this.close(); // Close the UI so the user can see the chat
        } else if (eventData.contains("\"action\":\"vacuum\"")) {
            java.util.List<String> responses = CaskaraAdminLogic.runVacuum();
            for (String response : responses) {
                this.playerRef.sendMessage(Message.raw(response));
            }
            this.close();
        } else if (eventData.contains("\"action\":\"globaldb\"")) {
            this.playerRef.sendMessage(Message.raw("Switched to Global DB Shell"));
            this.close();
        } else {
            this.playerRef.sendMessage(Message.raw("Clicked: " + eventData));
            this.close();
        }
    }
}
