package com.tuapp.compilador.core

import android.content.Context
import java.io.File

/**
 * d8, apksigner y zipalign son librerías Java puras embebidas en el propio
 * APK del creador de apps (ver DexStep.kt y PackageAndSignStep.kt, que
 * llaman a com.android.tools.r8.D8 y com.android.apksig.ApkSigner
 * directamente, y ZipAligner.kt para el alineado).
 *
 * ================================================================
 * POR QUÉ aapt2 SALE DE nativeLibraryDir Y NO DE filesDir (histórico)
 * ================================================================
 * Antes este archivo dejaba que el usuario eligiera aapt2 + android.jar con
 * un picker de carpetas (SAF) y los copiaba a filesDir/tools/, con
 * setExecutable(true, true). Eso SIEMPRE termina en
 * "Permission denied (error=13)" en Android 10+ (API 29+), sin importar el
 * chmod: desde Android 10 el sistema aplica una política W^X (write XOR
 * execute) sobre el directorio privado de la app vía SELinux — ningún
 * archivo escrito ahí en tiempo de ejecución (copiado por SAF, descargado,
 * etc.) puede ejecutarse como proceso, aunque el permiso Unix diga rwx.
 *
 * El ÚNICO directorio del sandbox de la app donde SÍ se permite ejecutar
 * binarios nativos es applicationInfo.nativeLibraryDir, y a ese directorio
 * solo puede escribir installd al instalar el APK, extrayendo lo que venga
 * en src/main/jniLibs/<ABI>/ (ver build.gradle.kts: jniLibs.srcDirs +
 * packaging.jniLibs.useLegacyPackaging = true, necesario para que SÍ se
 * extraiga a disco en vez de quedar mapeado dentro del .apk).
 *
 * Por eso ahora aapt2 va empaquetado DENTRO del APK, renombrado como si
 * fuera una librería nativa (libaapt2.so) en:
 *   app/src/main/jniLibs/arm64-v8a/libaapt2.so
 *   app/src/main/jniLibs/armeabi-v7a/libaapt2.so
 *   app/src/main/jniLibs/x86_64/libaapt2.so
 *   app/src/main/jniLibs/x86/libaapt2.so
 * (ver scripts/fetch-build-tools.sh, que descarga el binario aapt2
 * compilado para Android de cada ABI y lo deja ahí con el nombre correcto).
 * El APK pesa más así — a propósito, es el trade-off pedido: que funcione,
 * sin depender de que el usuario importe nada desde su almacenamiento.
 *
 * android.jar SÍ puede seguir viniendo como asset normal (no se ejecuta,
 * solo se lee), así que se empaqueta con la tarea "extraerAndroidJar" del
 * build.gradle.kts (toma el mismo android.jar del compileSdk que ya usa
 * este módulo para compilarse a sí mismo) y BuildTools la copia a
 * filesDir/tools/ la primera vez que arranca la app, igual que
 * kotlin-stdlib.jar.
 */
class BuildTools(private val toolsDir: File, private val nativeLibDir: File) {

    // aapt2 NUNCA se copia a filesDir: se ejecuta directo desde
    // nativeLibraryDir, el único lugar del sandbox exento de la
    // restricción W^X. Si esta ruta no existe es porque el APK no trae
    // libaapt2.so para el ABI de este dispositivo (ver verifyAll()).
    val aapt2: File get() = File(nativeLibDir, "libaapt2.so")

    val androidJar: File get() = File(toolsDir, "android.jar")

    val kotlinStdlibJar: File get() = File(toolsDir, "kotlin-stdlib.jar")

    fun verifyAll(): List<String> {
        val faltantes = mutableListOf<String>()
        if (!aapt2.exists() || !aapt2.canExecute()) faltantes.add(aapt2.name)
        if (!androidJar.exists()) faltantes.add(androidJar.name)
        if (!kotlinStdlibJar.exists()) faltantes.add(kotlinStdlibJar.name)
        return faltantes
    }

    /** Copia kotlin-stdlib.jar desde assets/ a filesDir/tools/ la primera vez
     * (viene embebida, ver build.gradle.kts: extraerKotlinStdlib). Se llama
     * automáticamente desde CompilationEngine antes de compilar. */
    fun asegurarKotlinStdlib(context: Context) {
        if (kotlinStdlibJar.exists()) return
        context.assets.open("kotlin-stdlib.jar").use { input ->
            kotlinStdlibJar.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** Copia android.jar desde assets/ a filesDir/tools/ la primera vez
     * (viene embebida, ver build.gradle.kts: extraerAndroidJar). Ya NO se
     * elige desde almacenamiento externo. */
    fun asegurarAndroidJar(context: Context) {
        if (androidJar.exists()) return
        context.assets.open("android.jar").use { input ->
            androidJar.outputStream().use { output -> input.copyTo(output) }
        }
    }

    companion object {

        private fun toolsDir(context: Context): File =
            File(context.filesDir, "tools").apply { mkdirs() }

        /** Punto de entrada único: aapt2 apunta a nativeLibraryDir (viene
         * empaquetado en el APK, no requiere copia); android.jar y
         * kotlin-stdlib.jar se aseguran en filesDir/tools/ desde assets si
         * hace falta. Todo automático, sin ningún picker ni permiso de
         * almacenamiento externo. */
        fun local(context: Context): BuildTools {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            return BuildTools(toolsDir(context), nativeLibDir).also {
                it.asegurarKotlinStdlib(context)
                it.asegurarAndroidJar(context)
            }
        }
    }
}
