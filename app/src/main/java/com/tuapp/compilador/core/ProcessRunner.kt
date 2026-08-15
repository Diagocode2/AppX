package com.tuapp.compilador.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ACTUALIZADO: ya solo hace falta para aapt2 (el único binario nativo que queda,
 * ver BuildTools.kt). d8 y apksigner se llaman ahora como librería Kotlin en
 * proceso (DexStep.kt, PackageAndSignStep.kt), así que se quitó `runJar` /
 * `app_process`, que era la parte más frágil de todo el motor (dependía de que
 * el dispositivo expusiera app_process sin restricciones de SELinux para apps
 * de terceros, algo que no está garantizado en ROMs recientes).
 */
object ProcessRunner {

    data class ExecResult(val exitCode: Int, val output: String)

    fun run(command: List<String>, workingDir: File, timeoutSec: Long = 120): ExecResult {
        val pb = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
        val process = pb.start()
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            return ExecResult(-1, "$output\n[Timeout tras ${timeoutSec}s]")
        }
        return ExecResult(process.exitValue(), output)
    }
}
