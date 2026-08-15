package com.tuapp.compilador.model

import java.io.File

/**
 * Representa un proyecto de app generado por el editor visual + lógica.
 * Todo apunta a carpetas dentro del almacenamiento propio de la app creadora,
 * para no depender de permisos de almacenamiento externo.
 */
data class ProjectModel(
    val projectId: String,
    val appName: String,
    val packageName: String,        // ej: com.midev.miapp
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val minSdk: Int = 21,
    val targetSdk: Int = 34,
    val rootDir: File               // carpeta base del proyecto dentro de filesDir de la app
) {
    val srcDir get() = File(rootDir, "src")           // .kt generados por el editor de lógica
    val resDir get() = File(rootDir, "res")            // recursos: layouts, drawables, strings
    val manifestFile get() = File(rootDir, "AndroidManifest.xml")
    val buildDir get() = File(rootDir, "build").apply { mkdirs() }
    val compiledResZip get() = File(buildDir, "resources.zip")
    val kotlinClassesDir get() = File(buildDir, "classes").apply { mkdirs() }  // salida de KotlinCompileStep
    val classesDexDir get() = File(buildDir, "dex").apply { mkdirs() }
    val unsignedApk get() = File(buildDir, "${appName}-unsigned.apk")
    val alignedApk get() = File(buildDir, "${appName}-aligned.apk")
    val signedApk get() = File(buildDir, "${appName}-signed.apk")

    fun ensureDirs() {
        rootDir.mkdirs(); srcDir.mkdirs(); resDir.mkdirs(); buildDir.mkdirs()
    }
}

/** Resultado de cada paso del pipeline de compilación */
sealed class StepResult {
    data class Success(val message: String = "") : StepResult()
    data class Failure(val message: String, val log: String = "") : StepResult()
}
