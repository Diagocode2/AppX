package com.tuapp.compilador.core

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Genera (o reutiliza) un keystore de debug PKCS12 con clave y certificado
 * autofirmado, directamente en el teléfono. Es el equivalente a lo que hace
 * `keytool -genkey` en un PC — pero Android no trae `keytool`, así que se usa
 * Bouncy Castle (Java puro, funciona igual en ART) para construir el
 * certificado X.509 a mano.
 *
 * Alias y contraseña ("androiddebugkey" / "android") están fijados aquí
 * porque son justo los que CompilationEngine pasa hardcodeados a
 * PackageAndSignStep.sign() — si cambias uno, cambia el otro.
 */
object DebugKeystoreGenerator {

    private const val ALIAS = "androiddebugkey"
    private const val PASSWORD = "android"

    fun getOrCreate(context: Context): File {
        val archivo = File(context.filesDir, "debug.keystore")
        if (archivo.exists()) return archivo

        // Android ya trae un provider "BC" recortado (sin CertificateFactory.X.509).
        // Security.addProvider() NO reemplaza uno existente con el mismo nombre,
        // así que hay que quitarlo primero e insertar el BouncyCastle completo.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val ahora = Date()
        val expira = Date(ahora.time + TimeUnit.DAYS.toMillis(365L * 30)) // 30 años, igual que el debug.keystore de Android Studio
        val serial = BigInteger(64, SecureRandom())
        val nombre = X500Name("CN=Android Debug,O=Android,C=US")

        val certHolder = JcaX509v3CertificateBuilder(
            nombre, serial, ahora, expira, nombre, keyPair.public
        ).build(JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private))

        val certificado = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certHolder)

        val keystore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keyPair.private, PASSWORD.toCharArray(), arrayOf(certificado))
        }

        archivo.outputStream().use { out -> keystore.store(out, PASSWORD.toCharArray()) }
        return archivo
    }
}
