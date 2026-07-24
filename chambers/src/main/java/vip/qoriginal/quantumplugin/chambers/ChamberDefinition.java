package vip.qoriginal.quantumplugin.chambers;

import org.bukkit.structure.Structure;

public record ChamberDefinition(
        String id,
        String title,
        String objective,
        Structure structure,
        boolean includeEntities,
        ChamberPosition spawn,
        ChamberRegion goal,
        int timeLimitSeconds,
        ChamberScripts scripts
) {
}
