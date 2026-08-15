// ---------------------------------------------------------------------------
// Módulo "app": el motor de compilación + una Activity mínima para poder
// instalarlo y probarlo en un teléfono real. La UI corre el pipeline
// COMPLETO (aapt2 + compilador de Kotlin embebido + D8 + empaquetado +
// alineado + firma) sobre un proyecto de prueba real escrito en Kotlin puro
// (ver KotlinCompileStep.kt) — no es el editor visual todavía.
// ---------------------------------------------------------------------------
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// El compilador de Kotlin embebido (KotlinCompileStep.kt) necesita
// kotlin-stdlib.jar como archivo REAL en disco para su classpath — no basta
// con que sus clases ya estén dexificadas dentro del propio APK del creador
// de apps (eso sirve para que ESTE módulo compile, no para que el
// compilador-como-librería pueda resolver tipos de Kotlin al compilar el
// proyecto del usuario). Esta configuración aparte resuelve el .jar tal
// cual, sin tocar el classpath normal de compilación de este módulo, y la
// tarea de abajo lo copia a assets/ para extraerlo en el dispositivo
// (ver BuildTools.asegurarKotlinStdlib). Es un jar pequeño y puro (sin
// binarios nativos), así que a diferencia de aapt2/android.jar (ver
// BuildTools.kt) esto NO es lo que había inflado el APK a ~100 MB.
// ---------------------------------------------------------------------------
val kotlinStdlibParaClasspath by configurations.creating

dependencies {
    kotlinStdlibParaClasspath("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}

val extraerKotlinStdlib = tasks.register<Copy>("extraerKotlinStdlib") {
    from(kotlinStdlibParaClasspath)
    into(layout.buildDirectory.dir("generated/kotlin-tools"))
    rename { "kotlin-stdlib.jar" }
}

tasks.named("preBuild") {
    dependsOn(extraerKotlinStdlib)
}

android {
    namespace = "com.tuapp.compilador"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tuapp.compilador"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Le pasa al test de ZipAligner la ruta del zipalign REAL para validar
    // contra él (ver app/src/test/.../ZipAlignerTest.kt). En CI, build.yml
    // pasa -PzipalignPath=... apuntando al build-tools ya instalado. Sin esa
    // propiedad (ej. corriendo local sin Android SDK), el test se salta solo
    // esa parte en vez de fallar.
    testOptions {
        unitTests.all {
            it.systemProperty(
                "zipalign.path",
                (project.findProperty("zipalignPath") as String?) ?: ""
            )
        }
    }

    // Varias dependencias (sobre todo bcprov-jdk18on + bcpkix-jdk18on, que son
    // multi-release jars, y a veces r8/apksig) traen el MISMO archivo de
    // metadatos en más de un jar (ej. META-INF/versions/9/OSGI-INF/MANIFEST.MF).
    // Gradle no sabe cuál de los duplicados quedarse al fusionar los recursos
    // Java del APK y falla mergeDebugJavaResource/mergeReleaseJavaResource.
    // Son solo metadatos OSGi/firmas que la app nunca lee en tiempo de
    // ejecución, así que es seguro quedarse con el primero y descartar el resto.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "module-info.class"
            )
        }
    }

    // Aquí es donde BuildTools.instalarDesdeArbol() copia aapt2 + android.jar
    // en tiempo de ejecución (el usuario los elige desde su almacenamiento,
    // ver MainActivity, botón "Elegir build tools") y donde
    // BuildTools.asegurarKotlinStdlib() copia kotlin-stdlib.jar (este sí
    // viaja embebido como asset, ver arriba). "gen" se añade como fuente de
    // assets para que la tarea "extraerKotlinStdlib" definida arriba quede
    // empaquetada en el APK de IdeAppDV.
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
            assets.srcDir(extraerKotlinStdlib.map { it.destinationDir })
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Para registerForActivityResult(ActivityResultContracts.OpenDocumentTree())
    // en MainActivity, el picker de carpetas del sistema.
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Para leer la carpeta que el usuario elige con el picker de Storage
    // Access Framework (BuildTools.instalarDesdeArbol) y copiar de ahí
    // aapt2 + android.jar, en vez de traerlos embebidos en assets/.
    implementation("androidx.documentfile:documentfile:1.0.1")

    // El compilador de Kotlin en sí, como librería embebida (misma idea que
    // D8/apksig más abajo: nada de invocar un `kotlinc` externo, que no
    // existe para Android). Ver KotlinCompileStep.kt para el aviso completo
    // sobre el riesgo de compatibilidad con ART: a diferencia de D8/apksig,
    // el compilador de Kotlin NO fue diseñado pensando en correr embebido
    // fuera de una JVM de escritorio, así que esto es lo más experimental
    // de todo el motor y hay que validarlo en dispositivo real.
    //
    // OJO CON EL TAMAÑO: esta librería es grande (incluye todo el front-end
    // del compilador) y SÍ se dexifica dentro del propio APK del creador de
    // apps -- a diferencia de aapt2/android.jar, esta parte no se puede
    // sacar a "elegir desde almacenamiento" porque el motor la necesita
    // como código Kotlin que se ejecuta en proceso, no como un archivo que
    // simplemente se lee. Es el costo real e inevitable de compilar Kotlin
    // arbitrario en el propio dispositivo.
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.24")

    // D8 (dexer) y R8 (shrinker) en un único jar. Es la MISMA librería que usa
    // el plugin de Gradle de Android por debajo. Es Java puro (sin JNI), así
    // que al compilar este proyecto, Gradle la dexifica junto con el resto de
    // la app y en el teléfono se llama como código Kotlin normal: NO hace
    // falta app_process, NO hace falta un d8.jar suelto en assets/.
    implementation("com.android.tools:r8:8.3.37")

    // apksig: la misma librería que usa `apksigner` por dentro. También Java
    // puro. Reemplaza a apksigner.jar + subproceso.
    // OJO (ver notas en PackageAndSignStep.kt): la versión oficial de Google
    // usa algunas clases de java.security que en ART pueden comportarse
    // distinto a la JVM de escritorio. Si falla en el teléfono, la
    // alternativa probada es el fork "apksig-android" vía JitPack
    // (com.github.MuntashirAkon:apksig-android) — el repositorio JitPack ya
    // está declarado en settings.gradle.kts por si hace falta activarlo:
    // implementation("com.github.MuntashirAkon:apksig-android:<version>")
    implementation("com.android.tools.build:apksig:8.10.1")

    // Para generar un keystore de debug REAL en el propio dispositivo (auto-
    // firmado, sin necesitar `keytool` que no existe en Android). Ver
    // core/DebugKeystoreGenerator.kt. Es Java puro, funciona igual en ART.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
}

// r8.jar y kotlin-compiler-embeddable.jar rondan varias decenas de MB cada
// uno porque incluyen sus respectivos front-ends/librerías completas. Para
// una build de producción del creador de apps conviene, más adelante, pasar
// R8/proguard sobre el propio motor para descartar de ambos todo lo que el
// motor realmente no llama en tiempo de ejecución.
