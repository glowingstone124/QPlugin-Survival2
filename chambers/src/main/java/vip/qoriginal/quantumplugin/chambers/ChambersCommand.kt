package vip.qoriginal.quantumplugin.chambers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ChambersCommand(
    private val chamberManager: ChamberManager,
) : CommandExecutor, TabCompleter {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.isEmpty()) return sendUsage(sender)
        return when (args[0].lowercase()) {
            "start" -> start(sender)
            "leave" -> leave(sender)
            "status" -> status(sender)
            "build" -> build(sender)
            "reload" -> reload(sender)
            else -> sendUsage(sender)
        }
    }

    private fun start(sender: CommandSender): Boolean {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("没有权限。", NamedTextColor.RED))
            return true
        }
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以开始测试。")
            return true
        }
        if (!chamberManager.startPractice(player)) {
            player.sendMessage(
                Component.text(
                    if (chamberManager.isReady()) {
                        "你已在测试流程中。"
                    } else {
                        "测试室尚未配置。"
                    },
                    NamedTextColor.RED,
                ),
            )
        }
        return true
    }

    private fun leave(sender: CommandSender): Boolean {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以退出测试。")
            return true
        }
        chamberManager.cancel(player, true)
        return true
    }

    private fun status(sender: CommandSender): Boolean {
        val prefix = if (sender is Player && chamberManager.isRunning(sender)) {
            "你正在测试流程中；"
        } else {
            ""
        }
        sender.sendMessage(
            "${prefix}当前共配置 ${chamberManager.chamberCount()} 个测试室。",
        )
        return true
    }

    private fun build(sender: CommandSender): Boolean {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("没有权限。", NamedTextColor.RED))
            return true
        }
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("只有玩家可以进入模板世界。")
            return true
        }
        chamberManager.enterTemplate(player)
        return true
    }

    private fun reload(sender: CommandSender): Boolean {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("没有权限。", NamedTextColor.RED))
            return true
        }
        try {
            chamberManager.reload()
            sender.sendMessage(
                "已重新加载 ${chamberManager.chamberCount()} 个测试室。",
            )
        } catch (exception: RuntimeException) {
            sender.sendMessage(
                Component.text(
                    "测试室配置无效：${exception.message}",
                    NamedTextColor.RED,
                ),
            )
        }
        return true
    }

    private fun sendUsage(sender: CommandSender): Boolean {
        sender.sendMessage("/chambers <start|leave|status|build|reload>")
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase()
        return SUBCOMMANDS.filter { it.startsWith(prefix) }
    }

    companion object {
        private const val ADMIN_PERMISSION = "qplugin.chambers.admin"
        private val SUBCOMMANDS = listOf(
            "start",
            "leave",
            "status",
            "build",
            "reload",
        )
    }
}
