// Build script raíz: solo declara versiones de plugins (apply false).
// La configuración real del módulo está en app/build.gradle.kts.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
