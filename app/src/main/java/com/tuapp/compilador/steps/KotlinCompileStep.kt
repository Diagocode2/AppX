package com.tuapp.compilador.steps

import com.tuapp.compilador.core.BuildTools
import com.tuapp.compilador.model.ProjectModel
import com.tuapp.compilador.model.StepResult
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Compila los .kt de project.srcDir a .class usando el propio compilador de
 * Kotlin como LIBRERÍA embebida (kotlin-compiler-embeddable, ver
 * app/build.gradle.kts), llamado en proceso — igual idea que D8 en
 * DexStep.kt. No existe un `kotlinc` nativo para invocar como subproceso en
 * Android, así que esta es la única forma de compilar Kotlin de verdad en
 * el propio dispositivo.
 *
 * Usa la entrada pública K2JVMCompiler().exec(PrintStream, vararg String) —
 * los mismos argumentos de línea de comandos que usarías con `kotlinc` en un
 * PC (-cp, -d, etc.) — en vez de construir K2JVMCompilerArguments a mano,
 * porque es la API más estable entre versiones del compilador.
 *
 * RIESGO CONOCIDO, sin validar todavía en dispositivo real (a diferencia de
 * D8 y apksig, que Google diseñó explícitamente para correr embebidos fuera
 * de una JVM de escritorio): el compilador de Kotlin usa internamente cosas
 * como ServiceLoader (para registrar las "extensiones" de su front-end),
 * manejo de hilos propio, y APIs de NIO/reflección que en ART pueden faltar
 * o comportarse distinto. Si K2JVMCompiler().exec(...) falla en el teléfono
 * con algo tipo NoClassDefFoundError, ServiceConfigurationError o
 * UnsupportedOperationException, es justo ese tipo de fricción — hay que
 * investigar caso por caso. No hay (a la fecha) un fork "para Android"
 * probado por la comunidad, a diferencia de apksig-android.
 */
class KotlinCompileStep(private val tools: BuildTools) {

    fun run(project: ProjectModel): StepResult {
        val fuentes = project.srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        if (fuentes.isEmpty()) {
            return StepResult.Failure("No hay archivos .kt en ${project.srcDir.absolutePath}")
        }

        val salida = project.kotlinClassesDir.apply {
            deleteRecursively()
            mkdirs()
        }

        val classpath = listOf(tools.androidJar, tools.kotlinStdlibJar)
            .filter { it.exists() }
            .joinToString(File.pathSeparator) { it.absolutePath }

        val argumentos = mutableListOf(
            "-cp", classpath,
            "-d", salida.absolutePath,
            // 1.8 y no 17: el bytecode de salida lo re-procesa D8 igual (ver
            // DexStep.kt), así que conviene el target más compatible posible
            // en vez de atarlo a la versión de JVM que usa el propio motor.
            "-jvm-target", "1.8",
            "-no-stdlib",
            "-no-reflect"
        )
        argumentos.addAll(fuentes.map { it.absolutePath })

        val bufferLog = ByteArrayOutputStream()
        val resultado: ExitCode = try {
            PrintStream(bufferLog, true, "UTF-8").use { ps ->
                K2JVMCompiler().exec(ps, *argumentos.toTypedArray())
            }
        } catch (e: Throwable) {
            // Cualquier fallo "de plomería" (ver aviso de la clase) se captura
            // acá en vez de tirar abajo toda la app.
            return StepResult.Failure(
                "El compilador de Kotlin embebido falló al ejecutarse (posible incompatibilidad con ART)",
                e.stackTraceToString()
            )
        }

        val logCompilador = bufferLog.toString("UTF-8")

        if (resultado != ExitCode.OK) {
            return StepResult.Failure("Compilación Kotlin falló ($resultado)", logCompilador)
        }

        val clases = salida.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        if (clases.isEmpty()) {
            return StepResult.Failure("El compilador terminó OK pero no generó ningún .class", logCompilador)
        }

        return StepResult.Success("Kotlin compilado: ${clases.size} archivo(s) .class")
    }
}
