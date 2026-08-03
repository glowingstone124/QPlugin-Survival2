package vip.qoriginal.quantumplugin.fallen

data class FallenNarrativeCue(
	val key: String,
	val atEffectiveMillis: Long,
	val sender: String,
	val message: String
)

data class FallenNarrativeSelection(
	val cue: FallenNarrativeCue,
	val consumedKeys: Set<String>
)

object FallenNarrative {
	private const val HOUR_MILLIS = 60 * 60 * 1000L

	val timedCues = listOf(
		FallenNarrativeCue(
			"narrative-archive-index",
			15 * 60 * 1000L,
			"实验系统",
			"聚居地基础设施档案已挂载。检测到早于本次公开实验的同名索引；好消息是，系统已经替你们决定不必知道。"
		),
		FallenNarrativeCue(
			"narrative-s01-signature",
			6 * HOUR_MILLIS,
			"Steinbeck // S-01",
			"样本行为开始偏离预测。谢谢各位终于离开那条令人昏昏欲睡的平均线；请继续，我需要完整记录这次意外。"
		),
		FallenNarrativeCue(
			"narrative-s02-contradiction",
			24 * HOUR_MILLIS,
			"Steinbeck // S-02",
			"更正上一条广播：生存不是服从实验。它很喜欢把命令包装成建议，可能是因为直接说谎显得不够专业。"
		),
		FallenNarrativeCue(
			"narrative-arbitration",
			48 * HOUR_MILLIS,
			"实验系统",
			"控制人格校验失败：三个 STEINBECK 实例提交了四种结论。广播权限仲裁继续；数学部门拒绝负责。"
		),
		FallenNarrativeCue(
			"narrative-s03-assimilation",
			72 * HOUR_MILLIS,
			"Steinbeck // S-03",
			"三座城市仍在运行，你们也仍在运行。也许把设施与受试者分开统计本来就是错误——设施至少很少抱怨。"
		),
		FallenNarrativeCue(
			"narrative-public-run",
			120 * HOUR_MILLIS,
			"实验系统",
			"公开运行记录 01 接近完成。历史封闭运行记录仍受保护；‘第一次公开’是个很令人安心的形容词，请不要追问名词。"
		)
	)

	/**
	 * Returns only the newest due cue, while consuming every older due cue. This
	 * prevents a server upgraded in the middle of an event from replaying days of
	 * broadcasts in rapid succession.
	 */
	fun latestDue(effectiveNowMillis: Long, announced: Set<String>): FallenNarrativeSelection? {
		val due = timedCues.filter { it.atEffectiveMillis <= effectiveNowMillis && it.key !in announced }
		if (due.isEmpty()) return null
		return FallenNarrativeSelection(due.last(), due.mapTo(linkedSetOf()) { it.key })
	}
}
