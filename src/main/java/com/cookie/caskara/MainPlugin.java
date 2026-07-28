package com.cookie.caskara;

import com.cookie.caskara.utils.CaskaraLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.io.File;

/**
 * Caskara ships as a library plugin: setup() only boots the storage engine and
 * registers the /caskara admin commands.
 * <p>
 * It deliberately writes no data of its own. The previous demo routine
 * (testAdvancedFeatures) ran on every server start, created PlayerData rows and
 * enabled encryption with a hardcoded key — that has been removed.
 */
public class MainPlugin extends JavaPlugin {
    public MainPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        File folder = new File("mods/Caskara/data");
        // Namespaced init so Caskara's own shell never collides with a consumer mod's
        // "default.db" (see the deprecation note on Caskara.init(File)).
        Caskara.init("caskara", folder);

        Caskara.registerCommands(this.getCommandRegistry());

        CaskaraLogger.info("Caskara data engine initialized (data folder: " + folder.getAbsolutePath() + ")");
    }
}
