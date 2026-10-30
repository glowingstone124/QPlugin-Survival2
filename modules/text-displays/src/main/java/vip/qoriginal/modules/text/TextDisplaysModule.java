package vip.qoriginal.modules.text;

import org.bukkit.entity.Player;
import vip.qoriginal.quantumplugin.hot.api.HotModule;
import vip.qoriginal.quantumplugin.hot.api.ModuleContext;
import java.util.List;

/** Created displays are persistent world entities; reloading their command does not remove them. */
public final class TextDisplaysModule implements HotModule {
    @Override public void prepare(ModuleContext context) {
        if (!context.hostName().equals("QuantumPlugin")) return;
        var displays = new TextDisplay();
        context.command("summontext", (sender, command, label, args) -> {
            if (!(sender instanceof Player player)) sender.sendMessage("只有玩家可以执行这个命令。");
            else displays.exec(player, String.join(" ", args));
            return true;
        }, (sender, command, alias, args) -> List.of());
    }
}
