package com.tuapp.compilador.steps

import com.android.apksig.ApkSigner
import com.tuapp.compilador.core.BuildTools
import com.tuapp.compilador.core.ZipAligner
import com.tuapp.compilador.model.ProjectModel
import com.tuapp.compilador.model.StepResult
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PackageAndSignStep(private val tools: BuildTools) {

    /** Combina resources.zip (ya tiene AndroidManifest.xml + res compilados) con classes.dex */
    fun pack(project: ProjectModel): StepResult {
        try {
            ZipOutputStream(project.unsignedApk.outputStream()).use { zos ->
                ZipFile(project.compiledResZip).use { zin ->
                    zin.entries().asSequence().forEach { entry ->
                        zos.putNextEntry(ZipEntry(entry.name))
                        zin.getInputStream(entry).copyTo(zos)
                        zos.closeEntry()
                    }
                }
                val dex = File(project.classesDexDir, "classes.dex")
                zos.putNextEntry(ZipEntry("classes.dex"))
                dex.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        } catch (e: Exception) {
            return StepResult.Failure("Empaquetado falló: ${e.message}")
        }
        return StepResult.Success("APK sin firmar generado: ${project.unsignedApk.name}")
    }

    /**
     * Antes: llamaba a `zipalign` nativo por subproceso. Ahora usa ZipAligner (Kotlin puro).
     * Ver las advertencias en ZipAligner.kt: es una primera versión, pendiente de validar
     * contra `zipalign -c -v 4` antes de confiar en ella para producción.
     */
    fun align(project: ProjectModel): StepResult {
        return try {
            ZipAligner.align(project.unsignedApk, project.alignedApk)
            StepResult.Success("APK alineado: ${project.alignedApk.name}")
        } catch (e: Exception) {
            StepResult.Failure("Alineado falló: ${e.message}", e.stackTraceToString())
        }
    }

    /**
     * Antes: apksigner.jar por subproceso vía app_process. Ahora usa la librería `apksig`
     * (com.android.tools.build:apksig, la misma que usa `apksigner` por dentro) directamente
     * en Kotlin — sin proceso externo.
     *
     * OJO — riesgo real a probar en dispositivo, no solo en la JVM de escritorio: apksig
     * depende de proveedores de java.security (firma RSA/EC, generación de digests) que en
     * ART a veces no están completos o se comportan distinto a la JVM de Android Studio.
     * Si `ApkSigner.Builder(...).build().sign()` falla en el teléfono con algo tipo
     * NoSuchAlgorithmException o similar, la alternativa ya probada por la comunidad es el
     * fork "apksig-android" (JitPack: com.github.MuntashirAkon:apksig-android), pensado
     * específicamente para correr sobre el runtime de Android.
     */
    fun sign(project: ProjectModel, keystore: File, keystorePass: String, keyAlias: String): StepResult {
        return try {
            val ks = KeyStore.getInstance("PKCS12").apply {
                keystore.inputStream().use { load(it, keystorePass.toCharArray()) }
            }
            val privateKey = ks.getKey(keyAlias, keystorePass.toCharArray()) as PrivateKey
            val cert = ks.getCertificate(keyAlias) as X509Certificate

            val signerConfig = ApkSigner.SignerConfig.Builder(
                keyAlias,
                privateKey,
                listOf(cert)
            ).build()

            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(project.alignedApk)
                .setOutputApk(project.signedApk)
                .setMinSdkVersion(project.minSdk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build()
                .sign()

            StepResult.Success("APK firmado listo: ${project.signedApk.absolutePath}")
        } catch (e: Exception) {
            StepResult.Failure("apksig falló al firmar", e.stackTraceToString())
        }
    }
}
