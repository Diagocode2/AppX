package com.tuapp.compilador.core

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Reemplaza al binario nativo `zipalign` (que, igual que aapt2, no viene compilado
 * para Android por Google) con una implementación pura en Kotlin: alinea a 4 bytes
 * las entradas SIN COMPRIMIR de un APK (requisito para que mmap() funcione
 * correctamente en tiempo de ejecución), igual que hace el zipalign real: rellenando
 * el "extra field" de la cabecera local de cada entrada (ver
 * https://developer.android.com/studio/command-line/zipalign — "The adjustment is
 * made by altering the size of the extra field in the zip Local File Header
 * sections").
 *
 * CORREGIDO (versión anterior tenía un bug real, no solo una limitación anotada):
 * el padding se calculaba usando solo el tamaño de la cabecera de CADA entrada de
 * forma aislada, sin acumular cuántos bytes llevaba escritos el archivo completo
 * hasta ese punto. Con un único archivo eso "funcionaba de casualidad", pero con
 * más de una entrada (el caso real: siempre hay varias) el offset acumulado se
 * desalinea después de la primera entrada. Se corrige envolviendo el
 * OutputStream con un contador de bytes propio (CountingOutputStream) y
 * calculando el padding usando el offset REAL donde empezaría esa entrada.
 *
 * Validado contra el `zipalign -c -v 4` real de Google en CI, ver
 * app/src/test/.../ZipAlignerTest.kt y el paso correspondiente en build.yml.
 *
 * Limitación conocida que SÍ se mantiene: no replica el modo "-p 16" / alineación
 * a 16 KB de página que exige zipalign moderno para bibliotecas nativas .so en
 * Android 15+. Mientras este motor no empaquete .so, esta versión de 4 bytes es
 * suficiente. Si más adelante se soportan librerías nativas en las apps
 * generadas, hay que ampliar este alineador para eso.
 */
object ZipAligner {

    private const val ALIGN_BYTES = 4
    private const val LOCAL_HEADER_FIXED_SIZE = 30 // ver especificación ZIP, sección 4.3.7

    fun align(input: File, output: File) {
        ZipFile(input).use { zin ->
            val counting = CountingOutputStream(output.outputStream().buffered())
            ZipOutputStream(counting).use { zout ->
                zin.entries().asSequence().forEach { entry ->
                    val bytes = zin.getInputStream(entry).use { it.readBytes() }
                    val newEntry = ZipEntry(entry.name)
                    val nameLen = entry.name.toByteArray(Charsets.UTF_8).size

                    if (entry.method == ZipEntry.STORED) {
                        newEntry.method = ZipEntry.STORED
                        newEntry.size = bytes.size.toLong()
                        newEntry.compressedSize = bytes.size.toLong()
                        newEntry.crc = CRC32().apply { update(bytes) }.value

                        // Offset donde empezaría la cabecera local de ESTA entrada
                        // = cuántos bytes lleva escritos el stream ahora mismo.
                        val localHeaderOffset = counting.count
                        newEntry.extra = paddingFor(localHeaderOffset, nameLen)
                    } else {
                        newEntry.method = ZipEntry.DEFLATED
                        // Las entradas comprimidas no necesitan alineación (no tiene
                        // sentido hacer mmap() de datos que hay que descomprimir
                        // primero), así que no se toca su "extra".
                    }

                    zout.putNextEntry(newEntry)
                    zout.write(bytes)
                    zout.closeEntry()
                }
            }
        }
    }

    /**
     * Calcula cuántos bytes de relleno hacen falta en el "extra field" para que
     * el CUERPO de la entrada (los datos, después de cabecera + nombre + extra)
     * caiga en un offset múltiplo de ALIGN_BYTES, dado el offset real y
     * acumulado donde arrancará esta cabecera local.
     */
    private fun paddingFor(localHeaderOffset: Long, nameLen: Int): ByteArray {
        val dataOffsetSinPadding = localHeaderOffset + LOCAL_HEADER_FIXED_SIZE + nameLen
        val remainder = (dataOffsetSinPadding % ALIGN_BYTES).toInt()
        val padLen = if (remainder == 0) 0 else ALIGN_BYTES - remainder
        return ByteArray(padLen)
    }

    /** OutputStream que solo cuenta cuántos bytes se le han escrito, sin alterar nada. */
    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
