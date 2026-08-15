package com.tuapp.compilador.steps

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.tuapp.compilador.core.BuildTools
import com.tuapp.compilador.model.ProjectModel
import com.tuapp.compilador.model.StepResult
import java.io.File

/**
 * Antes: lanzaba d8.jar como subproceso vía `app_process` (frágil: depende de que
 * el binario esté expuesto sin root, y en muchas ROMs modernas está restringido
 * por SELinux para apps de terceros).
 *
 * Ahora: D8 se usa como LIBRERÍA (com.android.tools:r8, ver app/build.gradle.kts.snippet),
 * llamada directamente en Kotlin. Como D8 es Java puro (sin JNI), cuando compilas
 * este proyecto en tu PC con Gradle, D8 queda dexificado dentro del propio APK del
 * creador de apps — en el teléfono ya no es un .jar externo que haya que ejecutar,
 * es una clase más de tu propio código. Elimina la necesidad de app_process y de
 * copiar d8.jar a assets/.
 *
 * `tools` se mantiene como parámetro por compatibilidad con CompilationEngine y
 * porque `android.jar` (classpath de la plataforma) se le pasa a D8Command como
 * "library file": el bytecode de entrada suele referenciar clases del framework
 * (Activity, TextView...) que D8 no necesita reempaquetar, pero sí le ayuda a
 * tenerlas disponibles como contexto para resolver tipos correctamente.
 */
class DexStep(private val tools: BuildTools) {

    fun run(project: ProjectModel, classFiles: List<File>): StepResult {
        if (classFiles.isEmpty()) {
            return StepResult.Failure("No hay archivos .class para dexificar")
        }

        return try {
            val command = D8Command.builder()
                .addProgramFiles(classFiles.map { it.toPath() })
                .addLibraryFiles(tools.androidJar.toPath())
                .setMode(CompilationMode.RELEASE)
                .setOutput(project.classesDexDir.toPath(), OutputMode.DexIndexed)
                .setMinApiLevel(project.minSdk)
                .build()

            D8.run(command)

            val dex = File(project.classesDexDir, "classes.dex")
            if (!dex.exists()) {
                StepResult.Failure("D8 terminó sin errores pero no generó classes.dex")
            } else {
                StepResult.Success("Dex generado: ${dex.length()} bytes (D8 embebido, sin subproceso)")
            }
        } catch (e: com.android.tools.r8.CompilationFailedException) {
            StepResult.Failure("D8 falló al compilar", e.message ?: e.toString())
        } catch (e: Exception) {
            // Cualquier otro fallo (p. ej. OutOfMemoryError encapsulado, o algo específico
            // de ART que no se ve en la JVM de escritorio durante pruebas locales).
            StepResult.Failure("Error inesperado ejecutando D8 embebido", e.stackTraceToString())
        }
    }
}
