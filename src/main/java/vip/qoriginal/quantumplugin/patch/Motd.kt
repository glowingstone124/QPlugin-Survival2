package vip.qoriginal.quantumplugin.patch
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.TextColor
import org.bukkit.ChatColor

class Motd {
    val server = Bukkit.getServer()
    fun change(){
        server.motd(Component.text("Quantum Original 2"))
    }
}