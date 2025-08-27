package vip.qoriginal.quantumplugin

import java.io.File
import java.util.jar.JarFile

object ClassScanner {
	fun scanPackage(pkg: String, classLoader: ClassLoader = Thread.currentThread().contextClassLoader): List<Class<*>> {
		val path = pkg.replace('.', '/')
		val resources = classLoader.getResources(path)
		val classes = mutableListOf<Class<*>>()

		while (resources.hasMoreElements()) {
			val url = resources.nextElement()
			if (url.protocol == "file") {
				val dir = File(url.toURI())
				classes += scanDir(pkg, dir, classLoader)
			} else if (url.protocol == "jar") {
				val jarPath = url.path.substringAfter("file:").substringBefore("!")
				val jarFile = JarFile(jarPath)
				for (entry in jarFile.entries()) {
					if (entry.name.endsWith(".class") && entry.name.startsWith(path)) {
						val className = entry.name.removeSuffix(".class").replace('/', '.')
						try {
							classes += classLoader.loadClass(className)
						} catch (_: Throwable) {
						}
					}
				}
			}
		}
		return classes
	}

	private fun scanDir(pkg: String, dir: File, classLoader: ClassLoader): List<Class<*>> {
		val classes = mutableListOf<Class<*>>()
		dir.walkTopDown().forEach { file ->
			if (file.isFile && file.name.endsWith(".class")) {
				val className = pkg + "." +
						file.relativeTo(dir).path.removeSuffix(".class").replace(File.separatorChar, '.')
				try {
					classes += classLoader.loadClass(className)
				} catch (_: Throwable) {
				}
			}
		}
		return classes
	}
}
