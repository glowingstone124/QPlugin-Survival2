package vip.qoriginal.quantumplugin.metro;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.IOException;

public class LocationAdapter extends TypeAdapter<Location> {
    @Override
    public void write(JsonWriter out, Location location) throws IOException {
        out.beginObject();
        out.name("world").value(location.getWorld().getName());
        out.name("x").value(location.getX());
        out.name("y").value(location.getY());
        out.name("z").value(location.getZ());
        out.endObject();
    }

    @Override
    public Location read(JsonReader in) throws IOException {
        in.beginObject();
        World world = null;
        double x = 0, y = 0, z = 0;
        while (in.hasNext()) {
            switch (in.nextName()) {
                case "world":
                    world = Bukkit.getWorld(in.nextString());
                    break;
                case "x":
                    x = in.nextDouble();
                    break;
                case "y":
                    y = in.nextDouble();
                    break;
                case "z":
                    z = in.nextDouble();
                    break;
            }
        }
        in.endObject();
        return new Location(world, x, y, z);
    }
}
