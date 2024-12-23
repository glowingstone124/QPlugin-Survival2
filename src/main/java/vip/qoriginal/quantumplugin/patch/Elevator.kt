package vip.qoriginal.quantumplugin.patch

import kotlin.math.pow

class Elevator {


	fun generateNonLinearSpeedCurve(
		maxSpeed: Double,
		targetHeight: Double,
		totalTime: Double,
		resolution: Int = 100): Map<Double, Double> {
		val steadyTime = totalTime * 0.6
		val accelTime = (totalTime - steadyTime) / 2
		val time = DoubleArray(resolution) { it * totalTime / (resolution - 1) }
		val speed = DoubleArray(resolution)
		fun accelCurve(t: Double, T: Double): Double {
			return maxSpeed * (t / T).pow(2)
		}
		for (i in time.indices) {
			when {
				time[i] < accelTime -> {
					speed[i] = accelCurve(time[i], accelTime)
				}
				time[i] in (accelTime..(accelTime + steadyTime)) -> {
					speed[i] = maxSpeed
				}
				else -> {
					val decelTime = time[i] - (accelTime + steadyTime)
					speed[i] = accelCurve(accelTime - decelTime, accelTime)
				}
			}
		}
		val height = speed.zip(time) { s, t -> s * (totalTime / resolution) }.sum()
		val scaleFactor = targetHeight / height
		for (i in speed.indices) {
			speed[i] *= scaleFactor
		}
		return time.zip(speed).toMap()
	}
}