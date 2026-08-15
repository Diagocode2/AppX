package com.tuapp.compilador.core

import com.tuapp.compilador.model.ProjectModel
import com.tuapp.compilador.model.StepResult
import com.tuapp.compilador.steps.DexStep
import com.tuapp.compilador.steps.KotlinCompileStep
import com.tuapp.compilador.steps.PackageAndSignStep
import com.tuapp.compilador.steps.ResourceCompileStep
import java.io.File

class CompilationEngine(context: android.content.Context) {

    // Ya NO se copian aapt2/android.jar desde assets (eso inflaba el APK a
    // ~100 MB): el usuario los elige una vez desde su almacenamiento
    // (MainActivity, botón "Elegir build tools" -> BuildTools.instalarDesdeArbol).
    // kotlin-stdlib.jar sí se asegura automáticamente aquí (BuildTools.local
    // ya la instala sola si hace falta): es un jar pequeño y puro que SÍ
    // viaja embebido como asset, ver BuildTools.kt.
    private val tools = BuildTools.local(context)
    private val resourceStep = ResourceCompileStep(tools)
    private val kotlinStep = KotlinCompileStep(tools)
    private val dexStep = DexStep(tools)
    private val packageStep = PackageAndSignStep(tools)

    /**
     * onProgress reporta cada etapa para que la UI del Creador de apps muestre
     * una barra de progreso tipo "Compilando recursos... / Compilando Kotlin... /
     * Generando dex... / Firmando...". Ya NO recibe classFiles desde afuera:
     * el código fuente del proyecto (project.srcDir, .kt) se compila acá mismo
     * con KotlinCompileStep, no se le pasa un .class ya compilado de antemano.
     */
    fun compile(
        project: ProjectModel,
        debugKeystore: File,
        onProgress: (String) -> Unit
    ): StepResult {
        val faltantes = tools.verifyAll()
        if (faltantes.isNotEmpty()) {
            return StepResult.Failure("Faltan build-tools: ${faltantes.joinToString()}")
        }
        project.ensureDirs()

        onProgress("Compilando recursos (aapt2)...")
        val r1 = resourceStep.run(project)
        if (r1 is StepResult.Failure) return r1

        onProgress("Compilando Kotlin (compilador embebido)...")
        val r2 = kotlinStep.run(project)
        if (r2 is StepResult.Failure) return r2

        onProgress("Generando bytecode Dalvik (d8)...")
        val classFiles = project.kotlinClassesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .toList()
        val r3 = dexStep.run(project, classFiles)
        if (r3 is StepResult.Failure) return r3

        onProgress("Empaquetando APK...")
        val r4 = packageStep.pack(project)
        if (r4 is StepResult.Failure) return r4

        onProgress("Alineando APK (zipalign)...")
        val r5 = packageStep.align(project)
        if (r5 is StepResult.Failure) return r5

        onProgress("Firmando APK (apksigner)...")
        val r6 = packageStep.sign(project, debugKeystore, "android", "androiddebugkey")
        if (r6 is StepResult.Failure) return r6

        onProgress("¡Listo!")
        return StepResult.Success("APK generado en ${project.signedApk.absolutePath}")
    }
}
