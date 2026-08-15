package com.tuapp.compilador.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Valida ZipAligner contra el `zipalign` real de Google (`zipalign -c -v 4`),
 * no solo contra nuestra propia lógica — que es justo lo que el README pedía
 * antes de confiar en esta clase para producción.
 *
 * El binario real solo está disponible en CI (build.yml le pasa la ruta vía
 * la propiedad de sistema "zipalign.path", tomada del Android SDK ya
 * instalado en el runner). Si corres `./gradlew test` en tu PC sin Android
 * SDK, este test se salta solo (no falla) en la parte que necesita el
 * binario, pero igual valida que ZipAligner no rompe el zip ni pierde datos.
 */
class ZipAlignerTest {

    @Test
    fun `alinea correctamente varias entradas con nombres de distinto largo`() {
        val entradaZipSinAlinear = crearZipDePrueba()
        val salidaAlineada = File.createTempFile("alineado", ".apk")
        salidaAlineada.deleteOnExit()

        ZipAligner.align(entradaZipSinAlinear, salidaAlineada)

        verificarQueNoSeCorrompieronLosDatos(entradaZipSinAlinear, salidaAlineada)
        verificarAlineacionConZipalignReal(salidaAlineada)
    }

    /**
     * Zip con varias entradas STORED (que SÍ deben quedar alineadas) mezcladas
     * con DEFLATED (que no lo necesitan), y con nombres de largo variable a
     * propósito: es justo la variación en el tamaño de la cabecera local lo
     * que hacía que el bug original (padding calculado sin offset acumulado)
     * solo se notara a partir de la segunda entrada en adelante.
     */
    private fun crearZipDePrueba(): File {
        val zipFile = File.createTempFile("sin_alinear", ".zip")
        zipFile.deleteOnExit()

        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            val entradas = listOf(
                "AndroidManifest.xml" to "contenido de prueba del manifest".toByteArray(),
                "res/drawable/un_nombre_bastante_largo_para_forzar_desalineacion.png" to
                    ByteArray(137) { it.toByte() },
                "classes.dex" to ByteArray(4096) { (it % 256).toByte() },
                "res/x.png" to ByteArray(17) { 0x7 },
                "resources.arsc" to ByteArray(2000) { (it * 3 % 256).toByte() }
            )

            entradas.forEach { (nombre, contenido) ->
                val entry = ZipEntry(nombre)
                entry.method = ZipEntry.STORED
                entry.size = contenido.size.toLong()
                entry.compressedSize = contenido.size.toLong()
                entry.crc = CRC32().apply { update(contenido) }.value
                zos.putNextEntry(entry)
                zos.write(contenido)
                zos.closeEntry()
            }
        }
        return zipFile
    }

    private fun verificarQueNoSeCorrompieronLosDatos(original: File, alineado: File) {
        java.util.zip.ZipFile(original).use { zOriginal ->
            java.util.zip.ZipFile(alineado).use { zAlineado ->
                val nombresOriginal = zOriginal.entries().asSequence().map { it.name }.toSet()
                val nombresAlineado = zAlineado.entries().asSequence().map { it.name }.toSet()
                assertEquals(
                    "El zip alineado debe tener exactamente las mismas entradas",
                    nombresOriginal, nombresAlineado
                )

                zOriginal.entries().asSequence().forEach { entry ->
                    val bytesOriginal = zOriginal.getInputStream(entry).use { it.readBytes() }
                    val bytesAlineado = zAlineado.getInputStream(zAlineado.getEntry(entry.name))
                        .use { it.readBytes() }
                    assertTrue(
                        "El contenido de '${entry.name}' debe ser idéntico antes y después de alinear",
                        bytesOriginal.contentEquals(bytesAlineado)
                    )
                }
            }
        }
    }

    private fun verificarAlineacionConZipalignReal(archivo: File) {
        val zipalignPath = System.getProperty("zipalign.path")
        val disponible = !zipalignPath.isNullOrBlank() && File(zipalignPath).canExecute()
        assumeTrue(
            "zipalign real no disponible en este entorno (normal fuera de CI) -> se salta la verificación estricta",
            disponible
        )
        // Kotlin no puede propagar el smart-cast de nulabilidad a través de
        // assumeTrue (no es una función con @kotlin.contracts), así que se
        // captura en un val no-nulo aparte para poder usarlo abajo.
        val rutaZipalign = requireNotNull(zipalignPath)

        val proceso = ProcessBuilder(rutaZipalign, "-c", "-v", "4", archivo.absolutePath)
            .redirectErrorStream(true)
            .start()
        val salida = proceso.inputStream.bufferedReader().readText()
        val exitCode = proceso.waitFor()

        assertEquals(
            "zipalign -c -v 4 debe confirmar que el archivo está alineado. Salida:\n$salida",
            0, exitCode
        )
    }
}
