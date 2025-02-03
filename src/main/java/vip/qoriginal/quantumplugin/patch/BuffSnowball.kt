package vip.qoriginal.quantumplugin.patch


import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.FireworkEffect
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.Color
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import vip.qoriginal.quantumplugin.QuantumPlugin
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

class BuffSnowball: CommandExecutor, Listener {
	val list = Files.readString(Path.of("newyear.txt")).split("\n".toRegex())
	val customSnowball = NamespacedKey(QuantumPlugin.getInstance(), "buff_snowball")
	val customSnowballTriggeredFirework = NamespacedKey(QuantumPlugin.getInstance(), "buff_snowball_firework")
	override fun onCommand(
		sender: CommandSender,
		command: Command,
		label: String,
		args: Array<out String>
	): Boolean {
		if (sender !is Player) {
			sender.sendMessage("Only players can use BuffSnowball")
			return true
		}
		val snowball = ItemStack(Material.SNOWBALL).add(15)
		val meta = snowball.itemMeta
		val selection = Random.nextInt(0, list.size)
		meta.apply {
			displayName(Component.text("新年团子").decoration(TextDecoration.BOLD, true).color(NamedTextColor.YELLOW))
			meta.lore(
				listOf<Component>(
					Component.text("试试看扔出去？").color(NamedTextColor.YELLOW),
					Component.text("此物品用于庆祝2025年农历春节").color(NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
					Component.text(list[selection]).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, true),
				)
			)
			persistentDataContainer.set(customSnowball, PersistentDataType.BYTE, 1)
			snowball.itemMeta = meta
		}
		sender.inventory.addItem(snowball)
		return true
	}
	@EventHandler
	fun onProjectileLaunch(event: ProjectileLaunchEvent) {
		val projectile = event.entity
		if (projectile is Snowball) {
			val shooter = projectile.shooter
			if (shooter is Player) {
				val itemInHand = shooter.inventory.itemInMainHand
				val isBuffSnowball = itemInHand.itemMeta?.persistentDataContainer?.has(customSnowball, PersistentDataType.BYTE) == true

				if (isBuffSnowball) {
					/*
					projectile.persistentDataContainer.set(customSnowball, PersistentDataType.BYTE, 1)
					Bukkit.getScheduler().runTaskTimer(QuantumPlugin.getInstance(), Runnable {
						if (!projectile.isDead && !projectile.isOnGround) {
							projectile.world.spawnParticle(
								Particle.FIREWORK,
								projectile.location,
								5,
								0.2,
								0.2,
								0.2,
								0.01
							)
						}
					}, 0L, 1L)
					*/
					event.isCancelled = true
					itemInHand.amount -= 1
				}
			}
		}
	}
	@EventHandler
	fun onProjectileHit(event: ProjectileHitEvent) {
		val projectile = event.entity
		val hitEntity = event.hitEntity
		if (projectile is Snowball) {
			val isBuffSnowball = projectile.persistentDataContainer.has(customSnowball, PersistentDataType.BYTE) == true
			if (isBuffSnowball) {
				val location = projectile.location
				val world = projectile.world

				val firework = world.spawn(location, org.bukkit.entity.Firework::class.java)

				val fireworkMeta = firework.fireworkMeta
				fireworkMeta.persistentDataContainer.set(customSnowball, PersistentDataType.BYTE, 1)
				fireworkMeta.addEffect(
					FireworkEffect.builder()
						.with(FireworkEffect.Type.BURST)
						.withColor(Color.RED, Color.YELLOW)
						.withFade(Color.ORANGE)
						.trail(true)
						.flicker(true)
						.build()
				)
				firework.fireworkMeta = fireworkMeta

				Bukkit.getScheduler().runTask(QuantumPlugin.getInstance(), Runnable {
					firework.detonate()
				})

				if (hitEntity is Player) {
					hitEntity.addPotionEffect(effectList[Random.nextInt(effectList.size)])
				}
			}
		}
	}

	@EventHandler
	fun onEntityDamage(event: EntityDamageEvent) {
		(event.damageSource.directEntity as? Firework)?.let { firework ->
			if (firework.persistentDataContainer.has(customSnowballTriggeredFirework, PersistentDataType.BYTE) == true) {
				event.isCancelled = true
			}
		}
	}


	val effectList = listOf(
		PotionEffect(
			PotionEffectType.REGENERATION,
			5 * 20,
			3
		),
		PotionEffect(
			PotionEffectType.HASTE,
			20 * 20,
			2
		),
		PotionEffect(
			PotionEffectType.GLOWING,
			10 * 20,
			3
		),
		PotionEffect(
			PotionEffectType.JUMP_BOOST,
			10 * 20,
			1
		)
	)
}