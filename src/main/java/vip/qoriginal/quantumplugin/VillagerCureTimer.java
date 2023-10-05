package vip.qoriginal.quantumplugin;

import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.ArrayList;

public class VillagerCureTimer implements Listener {
    static ArrayList<ZombieVillagerRec> zombieVillagers = new ArrayList<>();
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if(event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.INFECTION) {
            zombieVillagers.add(new ZombieVillagerRec((ZombieVillager) event.getEntity()));
        } else if(event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CURED) {
            event.getEntity().customName(null);
        }
    }

    public static class ZombieVillagerRec {
        boolean converts = false;
        int cure_duration = -1;
        ZombieVillager entity;
        public ZombieVillagerRec(ZombieVillager entity){
            this.entity = entity;
        }
    }
}
