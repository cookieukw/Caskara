package com.cookie.caskara.entities;

import java.util.ArrayList;
import java.util.List;

/**
 * Example of a complex entity with different types of data.
 */
public class PlayerStats {
    private String playerName;
    private int level;
    private double health;
    private boolean isVip;
    private List<String> inventory;
    private Location lastLocation;

    public PlayerStats(String playerName, int level, double health) {
        this.playerName = playerName;
        this.level = level;
        this.health = health;
        this.isVip = false;
        this.inventory = new ArrayList<>();
        this.lastLocation = new Location(0, 64, 0);
    }

    // Sub-object class
    public static class Location {
        public double x, y, z;
        public Location(double x, double y, double z) {
            this.x = x; this.y = y; this.z = z;
        }
    }

    // Getters and helper methods
    public void addItem(String item) { inventory.add(item); }
    public String getPlayerName() { return playerName; }
    public List<String> getInventory() { return inventory; }
    public void setLocation(double x, double y, double z) { this.lastLocation = new Location(x, y, z); }

    @Override
    public String toString() {
        return "PlayerStats{" +
                "name='" + playerName + '\'' +
                ", level=" + level +
                ", health=" + health +
                ", inventory=" + inventory +
                ", loc=" + lastLocation.x + "," + lastLocation.y + "," + lastLocation.z +
                '}';
    }
}
