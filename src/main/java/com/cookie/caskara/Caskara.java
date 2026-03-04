package com.cookie.caskara;

import com.cookie.caskara.db.Shell;
import com.cookie.caskara.db.Core;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Caskara API - Shell & Core Paradigm.
 * Entry point for managing data shells and cores.
 */
public class Caskara {
    private static File dataFolder;
    private static final Map<String, Shell> shells = new HashMap<>();

    /**
     * Initializes the Caskara API.
     * @param folder The root folder for all shells.
     */
    public static void init(File folder) {
        dataFolder = folder;
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    /**
     * Opens a global Shell by name.
     */
    public static Shell shell(String name) {
        return shells.computeIfAbsent("global:" + name, 
            n -> new Shell(new File(dataFolder, "global/" + name + ".db")));
    }

    /**
     * Opens the default global Shell.
     */
    public static Shell shell() {
        return shell("default");
    }

    /**
     * Opens a Shell dedicated to a specific world.
     */
    public static Shell shell(World world, String name) {
        return shells.computeIfAbsent("world:" + world.getName() + ":" + name, 
            n -> new Shell(new File(dataFolder, "worlds/" + world.getName() + "/" + name + ".db")));
    }

    /**
     * Quickly gets a Core from the default Shell.
     */
    public static <T> Core<T> core(Class<T> clazz) {
        return shell().core(clazz);
    }

    /**
     * SIMPLIFIED: Saves an object to the default database.
     */
    public static <T> void save(String id, T object) {
        core((Class<T>) object.getClass()).preserve(id, object);
    }

    /**
     * SIMPLIFIED: Loads an object from the default database.
     */
    public static <T> T load(String id, Class<T> clazz) {
        return core(clazz).extract(id).sync().orElse(null);
    }

    /**
     * SIMPLIFIED: Lists all objects of a certain type.
     */
    public static <T> java.util.List<T> list(Class<T> clazz) {
        return core(clazz).extractAll();
    }

    /**
     * SIMPLIFIED: Deletes an object from the default database.
     */
    public static <T> void delete(String id, Class<T> clazz) {
        core(clazz).discard(id);
    }
}
