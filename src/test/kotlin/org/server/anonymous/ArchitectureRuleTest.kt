package org.server.anonymous

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.walk

/**
 * Enforces the one-way dependency law: controller → business → data.
 * - controller: may NOT import org.server.anonymous.data
 * - business:   may NOT import org.server.anonymous.controller or any javafx.*
 * - data:       may NOT import org.server.anonymous.controller, org.server.anonymous.business, or javafx.*
 */
class ArchitectureRuleTest {
    private val sourceRoot: Path = Path.of("src/main/kotlin/org/server/anonymous")

    private val rules: Map<String, List<String>> =
        mapOf(
            "controller" to listOf("org.server.anonymous.data"),
            "business" to listOf("org.server.anonymous.controller", "javafx"),
            "data" to listOf("org.server.anonymous.controller", "org.server.anonymous.business", "javafx"),
        )

    @Test
    fun `rule engine detects a violating import`() {
        val engine = LayerRuleEngine(rules)
        val lines = listOf("package org.server.anonymous.business", "import javafx.beans.property.SimpleStringProperty")
        val violations = engine.findViolations("business", lines)
        assertTrue(violations.isNotEmpty(), "expected the javafx import to be flagged")
    }

    @Test
    fun `no source file violates the layer law`() {
        val violations = mutableListOf<String>()
        rules.forEach { (layer, forbiddenPrefixes) ->
            val dir = sourceRoot.resolve(layer)
            if (!Files.isDirectory(dir)) return@forEach
            dir.walk().filter { it.toString().endsWith(".kt") }.forEach { file ->
                violations += LayerRuleEngine(rules).findViolations(layer, Files.readAllLines(file))
            }
        }
        assertTrue(violations.isEmpty(), "Architecture violations:\n" + violations.joinToString("\n"))
    }
}

/**
 * Pure string rule engine: given the source lines of a file and its layer,
 * returns every import that violates the layer law.
 */
class LayerRuleEngine(
    private val rules: Map<String, List<String>>,
) {
    fun findViolations(
        layer: String,
        lines: List<String>,
    ): List<String> {
        val forbiddenPrefixes = rules[layer].orEmpty()
        return lines
            .filter { it.startsWith("import ") }
            .flatMap { importLine ->
                forbiddenPrefixes
                    .filter { importLine.contains("import $it") }
                    .map { "$importLine  (forbidden in layer '$layer')" }
            }
    }
}
