package vip.qoriginal.quantumplugin;

import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatCommandListener implements Listener {
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) throws Exception {
        if ((event.getMessage().contains("怎么") || event.getMessage().contains("如何")) && event.getMessage().length() > 4)
            event.getPlayer().sendMessage(Component.text("【友情提醒】本服务器已和 ")
                    .append(Component.text("G").color(TextColor.color(66, 133, 244)))
                    .append(Component.text("o").color(TextColor.color(233, 66, 53)))
                    .append(Component.text("o").color(TextColor.color(250, 187, 5)))
                    .append(Component.text("g").color(TextColor.color(66, 133, 244)))
                    .append(Component.text("l").color(TextColor.color(52, 168, 83)))
                    .append(Component.text("e").color(TextColor.color(233, 66, 53)))
                    .append(Component.text(" 达成合作，有不懂的可以直接查！")));
    }


}
