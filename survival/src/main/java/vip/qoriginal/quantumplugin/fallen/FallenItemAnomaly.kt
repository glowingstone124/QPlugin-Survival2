package vip.qoriginal.quantumplugin.fallen

object FallenItemAnomaly {
	const val VARIANT_COUNT = 12

	/**
	 * Produces a stable, uncommon anomaly from an item's identity. A null result
	 * means the label is normal; callers may safely rebuild the item later without
	 * changing whether it was marked.
	 */
	fun variant(seed: String, oneIn: Int): Int? {
		require(oneIn > 0) { "oneIn must be positive" }
		var hash = 0x811c9dc5.toInt()
		for (character in seed) {
			hash = (hash xor character.code) * 0x01000193
		}
		if (Math.floorMod(hash, oneIn) != 0) return null
		return Math.floorMod(hash xor 0x5bd1e995, VARIANT_COUNT)
	}
}
