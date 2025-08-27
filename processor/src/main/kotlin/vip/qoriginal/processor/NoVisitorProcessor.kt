package vip.qoriginal.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate
import java.io.OutputStreamWriter

class NoVisitorProcessor(
	private val codeGenerator: CodeGenerator,
	private val logger: KSPLogger
) : SymbolProcessor {

	override fun process(resolver: Resolver): List<KSAnnotated> {
		val symbols = resolver.getSymbolsWithAnnotation("vip.qoriginal.quantumplugin.adventures.NoVisitor")
			.filterIsInstance<KSFunctionDeclaration>()

		symbols.forEach { func ->
			if (!func.validate()) return@forEach

			val pkgName = func.packageName.asString()
			val className = func.parentDeclaration?.simpleName?.asString() ?: return@forEach
			val funcName = func.simpleName.asString()

			val file = codeGenerator.createNewFile(
				Dependencies(true, func.containingFile!!),
				pkgName,
				"${className}_${funcName}_Wrapper"
			)
			OutputStreamWriter(file).use { writer ->
				writer.write("""
                    package $pkgName

                    fun ${funcName}_safe(player: org.bukkit.entity.Player) {
                        if (player.scoreboardTags.contains("visitor")) return
                        $className().$funcName(player)
                    }
                """.trimIndent())
			}

			logger.info("生成 NoVisitor wrapper: $className.$funcName -> ${funcName}_safe")
		}

		return emptyList()
	}
}

