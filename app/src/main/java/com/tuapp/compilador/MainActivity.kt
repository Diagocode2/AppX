package com.tuapp.compilador

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.tuapp.compilador.core.BuildTools
import com.tuapp.compilador.core.CompilationEngine
import com.tuapp.compilador.core.DebugKeystoreGenerator
import com.tuapp.compilador.model.ProjectModel
import com.tuapp.compilador.model.StepResult
import java.io.File

/**
 * Banco de pruebas del motor, NO el editor visual todavía (eso es otro
 * proyecto pendiente). Este botón corre el pipeline COMPLETO y real:
 * aapt2 compile+link -> compilador de Kotlin embebido (KotlinCompileStep) ->
 * D8 (dex) -> empaquetado -> ZipAligner -> apksig, sobre un proyecto de
 * prueba real escrito en Kotlin puro (ver crearProyectoDePrueba, más abajo:
 * ya NO hay ningún .java ni plantilla precompilada de antemano), y firma
 * con un keystore de debug generado en el propio dispositivo
 * (DebugKeystoreGenerator, sin necesitar `keytool`). Si todo sale bien, el
 * botón "Instalar APK generado" abre el instalador del sistema con el APK
 * que el motor produjo — el cierre real del ciclo: el motor compiló Kotlin
 * de verdad, en el propio teléfono, de punta a punta.
 *
 * Ya NO hay picker de carpetas (SAF) para "elegir build tools": aapt2 viene
 * empaquetado dentro del propio APK (ver BuildTools.kt y
 * app/src/main/jniLibs/) y android.jar + kotlin-stdlib.jar se extraen solos
 * de assets/ al arrancar. actualizarEstadoBuildTools() ahora es solo un
 * chequeo de sanidad (por si el APK no trae libaapt2.so para el ABI de
 * este dispositivo en particular), no un paso que el usuario deba disparar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var textoEstado: TextView
    private lateinit var textoBuildTools: TextView
    private lateinit var botonCompilar: Button
    private lateinit var botonInstalar: Button
    private val ui = Handler(Looper.getMainLooper())

    private var apkGenerado: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textoEstado = findViewById(R.id.textoEstado)
        textoBuildTools = findViewById(R.id.textoBuildTools)
        botonCompilar = findViewById(R.id.botonCompilar)
        botonInstalar = findViewById(R.id.botonInstalar)

        botonCompilar.setOnClickListener {
            botonCompilar.isEnabled = false
            botonInstalar.isEnabled = false
            apkGenerado = null
            textoEstado.text = ""
            log("Iniciando compilación...\n")
            Thread { correrPipelineCompleto() }.start()
        }

        botonInstalar.setOnClickListener { instalarApkGenerado() }

        actualizarEstadoBuildTools()
    }

    private fun actualizarEstadoBuildTools() {
        val faltantes = BuildTools.local(applicationContext).verifyAll()
        if (faltantes.isEmpty()) {
            textoBuildTools.text = getString(R.string.build_tools_configurados)
            botonCompilar.isEnabled = true
        } else {
            // No debería pasar en un build normal: significa que el APK no
            // trae libaapt2.so para el ABI de este dispositivo, o que faltó
            // correr scripts/fetch-build-tools.sh antes de compilar el
            // propio creador de apps.
            textoBuildTools.text = getString(R.string.build_tools_incompletos, faltantes.joinToString())
            botonCompilar.isEnabled = false
        }
    }

    private fun correrPipelineCompleto() {
        try {
            log("Preparando proyecto y keystore de debug...")
            val keystore = DebugKeystoreGenerator.getOrCreate(applicationContext)

            val project = crearProyectoDePrueba()
            log("Proyecto de prueba en ${project.rootDir.absolutePath}")

            val engine = CompilationEngine(applicationContext)
            val resultado = engine.compile(
                project = project,
                debugKeystore = keystore,
                onProgress = { mensaje -> log(mensaje) }
            )

            when (resultado) {
                is StepResult.Success -> {
                    log("\nÉXITO: ${resultado.message}")
                    apkGenerado = project.signedApk
                    ui.post { botonInstalar.isEnabled = true }
                }
                is StepResult.Failure -> {
                    log("\nFALLÓ: ${resultado.message}")
                    if (resultado.log.isNotBlank()) log(resultado.log)
                }
            }
        } catch (e: Exception) {
            log("\nExcepción inesperada: ${e.stackTraceToString()}")
        } finally {
            ui.post { botonCompilar.isEnabled = true }
        }
    }

    /**
     * Proyecto mínimo pero real: un string, un manifest, y ahora también el
     * propio código fuente en Kotlin (antes era una plantilla Java YA
     * compilada de antemano por Gradle en el PC; ahora KotlinCompileStep la
     * compila de verdad, en el teléfono, con el compilador embebido). Nada
     * de esto es simulado — es justo lo que ResourceCompileStep +
     * KotlinCompileStep + DexStep esperan como entrada.
     */
    private fun crearProyectoDePrueba(): ProjectModel {
        val project = ProjectModel(
            projectId = "prueba",
            appName = "ProyectoPrueba",
            packageName = "com.tuapp.compilador.prueba",
            rootDir = File(filesDir, "proyectos/prueba")
        )
        project.ensureDirs()

        File(project.resDir, "values").apply { mkdirs() }.also { valuesDir ->
            File(valuesDir, "strings.xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">ProyectoPrueba</string>
                </resources>
                """.trimIndent()
            )
        }

        // A propósito no referencia ninguna clase R (ni R.layout, ni R.id):
        // R.java se genera distinto para cada proyecto que compile el motor,
        // así que esta pantalla de prueba construye su UI en código puro.
        File(project.srcDir, "PantallaPrincipal.kt").writeText(
            """
            package com.tuapp.compilador.prueba

            import android.app.Activity
            import android.graphics.Color
            import android.os.Bundle
            import android.view.Gravity
            import android.widget.TextView

            class PantallaPrincipal : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)

                    val texto = TextView(this)
                    texto.text = "¡Esta app la compiló tu propio motor, en Kotlin!"
                    texto.textSize = 20f
                    texto.gravity = Gravity.CENTER
                    texto.setPadding(48, 48, 48, 48)
                    texto.setTextColor(Color.WHITE)
                    texto.setBackgroundColor(Color.DKGRAY)

                    setContentView(texto)
                }
            }
            """.trimIndent()
        )

        project.manifestFile.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="${project.packageName}">
                <application android:label="@string/app_name">
                    <activity android:name=".PantallaPrincipal" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent()
        )

        return project
    }

    private fun instalarApkGenerado() {
        val apk = apkGenerado ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            log("\nActiva primero el permiso 'Instalar apps desconocidas' para IdeAppDV, y vuelve a tocar el botón.")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun log(linea: String) {
        ui.post { textoEstado.append("\n$linea") }
    }
}
