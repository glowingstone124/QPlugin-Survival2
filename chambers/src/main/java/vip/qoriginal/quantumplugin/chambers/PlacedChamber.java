package vip.qoriginal.quantumplugin.chambers;

import org.bukkit.Location;

public record PlacedChamber(ChamberDefinition definition, Location origin) {
    public PlacedChamber {
        origin = origin.clone();
    }
}
