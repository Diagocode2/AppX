package com.tuapp.compilador.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * d8, apksigner y zipalign son librerías Java puras embebidas en el propio
 * APK del creador de apps (ver DexStep.kt y PackageAndSignStep.kt, que
 * llaman a com.android.tools.r8.D8 y com.android.apksig.ApkSigner
 * directamente, y ZipAligner.kt para el alineado).
 *
 * Lo único que SIGUE siendo un binario nativo (ELF) sin alternativa Java
 * pura es `aapt2`, y además hace falta `android.jar` (el stub de plataforma
 * que aapt2 usa para el "link"). Juntos pesan decenas de MB por ABI — si se
 * empaquetan como asset dentro del APK (como se hacía antes con
 * installFromAssets(), ver historial), el APK del propio creador de apps
 * termina pesando ~100 MB, que es justo lo que NO se quiere para algo que
 * el usuario debe poder bajar rápido.
 *
 * Por eso ahora el flujo es: el usuario ya tiene aapt2 + android.jar en
 * algún lado de su almacenamiento (los bajó una vez, o los sacó de un SDK
 * de Android instalado, o de una build anterior), y desde la UI
 * (MainActivity, botón "Elegir build tools") los selecciona con el picker
 * de carpetas del sistema (Storage Access Framework). Esta clase copia
 * esos dos archivos a filesDir/tools/ (necesario porque un content:// de
 * SAF no es ejecutable directamente: aapt2 SOLO puede correr desde un
 * archivo real en almacenamiento propio de la app, con permiso de
 * ejecución) y de ahí en adelante el motor los usa como siempre.
 *
 * installFromAssets() se conserva por si en el futuro se quiere volver a
 * empaquetar los build-tools dentro del APK (p. ej. una build "todo
 * incluido"), pero YA NO es el camino usado por defecto.
 */
class BuildTools(private val toolsDir: File) {

    val aapt2: File get() = File(toolsDir, "aapt2")
    val androidJar: File get() = File(toolsDir, "android.jar")

    // A diferencia de aapt2/android.jar (que el usuario elige desde su
    // almacenamiento, ver instalarDesdeArbol), este SÍ viaja embebido como
    // asset del propio APK (ver build.gradle.kts: extraerKotlinStdlib) — es
    // un jar Kotlin puro y pequeño, no un binario nativo por ABI, así que no
    // es lo que había inflado el APK a ~100 MB. KotlinCompileStep lo necesita
    // como classpath real en disco para compilar el código del usuario.
    val kotlinStdlibJar: File get() = File(toolsDir, "kotlin-stdlib.jar")

    fun verifyAll(): List<String> {
        val faltantes = mutableListOf<String>()
        if (!aapt2.exists() || !aapt2.canExecute()) faltantes.add(aapt2.name)
        if (!androidJar.exists()) faltantes.add(androidJar.name)
        if (!kotlinStdlibJar.exists()) faltantes.add(kotlinStdlibJar.name)
        return faltantes
    }

    /** Copia kotlin-stdlib.jar desde assets/ a filesDir/tools/ la primera vez
     * (no hace falta que el usuario la elija: siempre viene embebida). Se
     * llama automáticamente desde CompilationEngine antes de compilar. */
    fun asegurarKotlinStdlib(context: Context) {
        if (kotlinStdlibJar.exists()) return
        context.assets.open("kotlin-stdlib.jar").use { input ->
            kotlinStdlibJar.outputStream().use { output -> input.copyTo(output) }
        }
    }

    companion object {

        private fun toolsDir(context: Context): File =
            File(context.filesDir, "tools").apply { mkdirs() }

        /** Apunta a filesDir/tools. aapt2/android.jar deben haberse instalado
         * antes desde almacenamiento (instalarDesdeArbol) o desde assets
         * (installFromAssets); kotlin-stdlib.jar SÍ se asegura aquí mismo en
         * cada llamada porque siempre viene embebida y es una copia barata
         * (si ya existe, no hace nada). */
        fun local(context: Context): BuildTools =
            BuildTools(toolsDir(context)).also { it.asegurarKotlinStdlib(context) }

        /**
         * Recorre (un nivel, y si no encuentra ahí, dos niveles — por si el
         * usuario eligió la carpeta padre en vez de la carpeta build-tools/<abi>
         * exacta) la carpeta elegida por el usuario con el picker de SAF,
         * busca "aapt2" y "android.jar" por nombre (sin importar mayúsculas) y
         * los copia a filesDir/tools/, dando permiso de ejecución a aapt2.
         *
         * Devuelve la lista de archivos que NO se encontraron (vacía si se
         * encontraron y copiaron los dos).
         */
        fun instalarDesdeArbol(context: Context, arbolUri: Uri): List<String> {
            val raiz = DocumentFile.fromTreeUri(context, arbolUri)
                ?: return listOf("aapt2", "android.jar")

            val aapt2Doc = buscarPorNombre(raiz, "aapt2")
            val androidJarDoc = buscarPorNombre(raiz, "android.jar")

            val destino = toolsDir(context)
            val faltantes = mutableListOf<String>()

            if (aapt2Doc != null) {
                copiarDocumento(context, aapt2Doc, File(destino, "aapt2"))
                File(destino, "aapt2").setExecutable(true, true)
            } else {
                faltantes.add("aapt2")
            }

            if (androidJarDoc != null) {
                copiarDocumento(context, androidJarDoc, File(destino, "android.jar"))
            } else {
                faltantes.add("android.jar")
            }

            return faltantes
        }

        /** Busca un archivo por nombre exacto (sin distinguir mayúsculas) hasta
         * dos niveles de profundidad dentro de [raiz], para tolerar que el
         * usuario elija la carpeta contenedora en vez de la carpeta exacta
         * (ej. elige "build-tools" en vez de "build-tools/arm64-v8a"). */
        private fun buscarPorNombre(raiz: DocumentFile, nombre: String): DocumentFile? {
            raiz.listFiles().forEach { hijo ->
                if (hijo.isFile && hijo.name?.equals(nombre, ignoreCase = true) == true) {
                    return hijo
                }
            }
            raiz.listFiles().forEach { hijo ->
                if (hijo.isDirectory) {
                    hijo.listFiles().forEach { nieto ->
                        if (nieto.isFile && nieto.name?.equals(nombre, ignoreCase = true) == true) {
                            return nieto
                        }
                    }
                }
            }
            return null
        }

        private fun copiarDocumento(context: Context, origen: DocumentFile, destino: File) {
            context.contentResolver.openInputStream(origen.uri)?.use { input ->
                destino.outputStream().use { output -> input.copyTo(output) }
            }
        }

        /**
         * Camino antiguo: copia aapt2 y android.jar desde assets/build-tools/<abi>/
         * (si es que el APK los trae embebidos). Ya no se usa por defecto porque
         * infla el APK del creador de apps a ~100 MB — se deja solo como opción
         * de respaldo.
         */
        fun installFromAssets(context: Context): BuildTools {
            val abi = android.os.Build.SUPPORTED_ABIS.first()
            val destDir = toolsDir(context)
            val assetPath = "build-tools/$abi"

            context.assets.list(assetPath)?.forEach { fileName ->
                val outFile = File(destDir, fileName)
                if (!outFile.exists()) {
                    context.assets.open("$assetPath/$fileName").use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (fileName == "aapt2") {
                        outFile.setExecutable(true, true)
                    }
                }
            }
            return BuildTools(destDir)
        }
    }
}
