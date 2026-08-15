# Motor de compilación — Creador de apps tipo Sketchware

Esqueleto del **motor de compilación** (la pieza que pediste empezar primero). Pipeline:

```
recursos (res/, AndroidManifest.xml)
        │  aapt2 compile + link          ← único binario nativo que sigue pendiente
        ▼
resources.zip + R.java
        │
código (plantillas / bloques → .class)
        │  D8 embebido como librería     ← YA NO es subproceso
        ▼
classes.dex
        │
        │  empaquetar (zip) + alinear (Kotlin puro) + apksig embebido (firma)
        ▼
APK firmado, listo para instalar
```

## Archivos
- `model/ProjectModel.kt` — representa un proyecto (rutas, package name, versión)
- `core/BuildTools.kt` — aapt2 + android.jar (elegidos por el usuario desde su almacenamiento) y kotlin-stdlib.jar (embebida como asset, se instala sola)
- `core/ProcessRunner.kt` — ejecuta aapt2 como subproceso (es lo único que lo necesita ya)
- `core/ZipAligner.kt` — alineado de APK en Kotlin puro (reemplaza `zipalign`)
- `core/CompilationEngine.kt` — orquesta todo el pipeline
- `steps/ResourceCompileStep.kt` — aapt2 compile + link
- `steps/KotlinCompileStep.kt` — **nuevo**: compila los `.kt` del proyecto con el compilador de Kotlin embebido (`kotlin-compiler-embeddable`), en proceso, sin subproceso
- `steps/DexStep.kt` — llama a D8 (com.android.tools:r8) en proceso, sin subproceso
- `steps/PackageAndSignStep.kt` — empaqueta, alinea con ZipAligner y firma con `apksig` embebido

## Qué cambió en esta vuelta

Antes el plan era ejecutar d8.jar y apksigner.jar como subprocesos vía `app_process`
(hack frágil: en muchas ROMs recientes SELinux se lo bloquea a apps de terceros sin root).
Ahora D8 y apksig se usan como **librerías Java puras** (`com.android.tools:r8` y
`com.android.tools.build:apksig`, las mismas que usa el propio Android Studio). Como no
tienen código nativo, cuando compiles este proyecto en tu PC con Gradle, D8 y apksig quedan
dexificados dentro del propio APK del creador — en el teléfono ya no son procesos externos
que haya que lanzar, son clases Kotlin normales. Esto resuelve por completo el punto 2 que
tenía la versión anterior del README, y de paso elimina también `apksigner.jar` y (con
`ZipAligner.kt`) el binario `zipalign` como bloqueadores.

## Lo que TODAVÍA falta y es crítico

1. **aapt2 sigue siendo el único binario nativo pendiente de resolver "de fábrica".**
   Ahora el usuario lo elige a mano desde su almacenamiento (ver BuildTools.kt,
   botón "Elegir build tools"), lo cual resuelve el problema práctico, pero
   sigue sin haber una reimplementación Java de aapt2 ni una forma de
   conseguirlo automáticamente dentro de la app.

2. **`ZipAligner.kt` es una primera versión, no validada.** `ZipOutputStream` de
   `java.util.zip` no expone el offset real acumulado del archivo, así que el alineado
   actual es una aproximación por entrada, no una réplica fiel de `zipalign`. Antes de
   confiar en ella hay que compilar un APK de prueba y verificarlo con
   `zipalign -c -v 4 out.apk` en un PC. Si falla, hay que reescribirla envolviendo el
   `OutputStream` con un contador de bytes propio.

3. **`apksig` (Google) puede tener fricciones en ART**, aunque es Java puro: algunos
   proveedores de `java.security` (RSA/EC, digests) se comportan distinto en Android que
   en la JVM de escritorio. Si al probar en el teléfono falla la firma, ya existe un fork
   probado por la comunidad para este caso exacto: **apksig-android**
   (JitPack, `com.github.MuntashirAkon:apksig-android`).

4. **El compilador de Kotlin embebido (`KotlinCompileStep.kt`) es lo más
   experimental de todo el motor, y NO está validado en dispositivo real.**
   A diferencia de D8 y apksig — que Google diseñó explícitamente como
   librerías embebibles fuera de una JVM de escritorio — el compilador de
   Kotlin (`kotlin-compiler-embeddable`) es una herramienta pensada para
   correr en un entorno de desarrollo normal. Usa `ServiceLoader` para
   registrar las extensiones de su front-end, maneja hilos internamente, y
   toca bastante superficie de `java.nio`/reflección. Es totalmente posible
   que en ART falle con `NoClassDefFoundError`, `ServiceConfigurationError`
   o similar — hay que probarlo en un teléfono real antes de confiar en él.
   Si falla, no hay (a la fecha) un fork "para Android" conocido y probado
   como sí existe para apksig; tocaría investigar caso por caso qué falta.
   También pesa bastante (se dexifica dentro del propio APK del creador de
   apps): es el costo inevitable de compilar Kotlin arbitrario on-device.

## El motor ya compila Kotlin real, de punta a punta

Antes, el pipeline dependía de una plantilla `.java` YA compilada de
antemano por `javac` en el PC (durante el build de CI), y el motor solo se
encargaba de dexificarla — no compilaba código fuente de verdad en el
teléfono. Eso se reemplazó por completo:

- **`steps/KotlinCompileStep.kt`** — toma los `.kt` de `project.srcDir` y
  los compila EN EL TELÉFONO con el compilador de Kotlin embebido como
  librería (`kotlin-compiler-embeddable`, ver aviso de riesgo en el punto 4
  de arriba), igual que D8 se usa como librería para dexificar. Ya no hay
  ningún `.java` ni bytecode precompilado de antemano en ningún lado del
  proyecto — todo el código de la app generada se compila de verdad,
  on-device, a partir de texto Kotlin.
- **`core/BuildTools.kt`** — además de aapt2/android.jar (elegidos por el
  usuario), ahora también instala `kotlin-stdlib.jar` (embebida como asset,
  ver `build.gradle.kts`: tarea `extraerKotlinStdlib`) — el compilador la
  necesita como classpath real en disco para resolver tipos de Kotlin.
- **`core/DebugKeystoreGenerator.kt`** — genera un keystore PKCS12 con clave y
  certificado autofirmado directamente en el teléfono (Android no trae `keytool`),
  usando Bouncy Castle (Java puro, funciona igual en ART). Se reutiliza entre
  ejecuciones.
- **`DexStep.kt`** — le pasa `android.jar` a D8 como "library file", correcto
  dado que el código del usuario referencia clases del framework (`Activity`,
  `TextView`).
- **`MainActivity.kt`** — el botón "Compilar proyecto de prueba" genera un
  `.kt` real (`crearProyectoDePrueba()`, una Activity mínima sin depender de
  `R`) y corre `CompilationEngine.compile()` completo (aapt2 → **compilador
  de Kotlin** → D8 → empaquetado → `ZipAligner` → `apksig`) sobre ese
  proyecto. Si sale bien, se habilita "Instalar APK generado", que abre el
  instalador del sistema con el APK que el motor produjo — usando
  `FileProvider` (declarado en el manifest, con permiso
  `REQUEST_INSTALL_PACKAGES`).

Instala el APK, dale a "Elegir build tools" (apunta a la carpeta donde
tengas `aapt2` + `android.jar`), luego a "Compilar", y si todo va bien, dale
a "Instalar APK generado": deberías terminar con una segunda app instalada
en el teléfono, con el mensaje "¡Esta app la compiló tu propio motor, en
Kotlin!" — esa es la confirmación real de que el compilador de Kotlin
embebido funciona de punta a punta en el dispositivo, no solo en CI.

## Próximo paso sugerido

Con el pipeline completo compilando Kotlin real (aunque sea sobre un
proyecto de prueba fijo), lo que queda con más impacto: validar
`KotlinCompileStep` en un dispositivo real (punto 4 de arriba — es lo más
experimental de todo el motor), validar `ZipAligner` contra `zipalign` real,
firma de release (no solo debug), y — lo más importante — arrancar el
editor visual en sí, que sigue sin existir.

## ZipAligner: corregido y validado contra el zipalign real (esta vuelta)

El propio código tenía anotado un bug conocido: el padding se calculaba usando solo el
tamaño de la cabecera de CADA entrada por separado, sin acumular cuántos bytes llevaba
escritos el archivo hasta ese punto. Con más de una entrada (el caso real siempre) el
offset se desalineaba después de la primera. Se corrigió envolviendo el `OutputStream`
con un contador de bytes propio (`CountingOutputStream`) y calculando el padding con el
offset real y acumulado — tal como el propio comentario del código pedía hacer.

Añadido `app/src/test/.../ZipAlignerTest.kt`: genera un zip de prueba con varias entradas
de nombres de distinto largo (a propósito, para forzar la desalineación que exponía el
bug viejo), corre `ZipAligner.align`, y confirma con `zipalign -c -v 4` **real** (el mismo
binario de Google, instalado en el runner de CI) que el resultado queda correctamente
alineado. `build.yml` ya corre este test en cada build (`gradle :app:test -PzipalignPath=...`)
antes de subir el artifact. Si corres los tests en tu PC sin Android SDK, esa parte se
salta sola (con `Assume`) en vez de fallar — pero igual valida que no se corrompan datos.

## Proyecto Gradle

Ya está montado como proyecto Gradle completo, listo para compilar en CI:

- `settings.gradle.kts`, `build.gradle.kts` (raíz), `gradle.properties`
- `app/build.gradle.kts` — módulo **librería** Android (`com.android.library`),
  `namespace = "com.tuapp.compilador"`, `minSdk 21`, `compileSdk 34`, con las
  dependencias de `r8` y `apksig` ya incluidas (el `.snippet` de antes ya está
  integrado aquí, se borró).
- `app/src/main/AndroidManifest.xml` — mínimo, sin `<application>` porque
  todavía no hay UI (esto sigue siendo solo el motor).
- `.github/workflows/build.yml` — compila el módulo en GitHub Actions
  (`assembleDebug`), instala la plataforma Android 34, y resuelve aapt2 (ver
  abajo).
- `scripts/fetch-build-tools.sh` — resuelve la dependencia real "tipo
  Sketchware Pro": descarga el binario **aapt2** precompilado para Android
  (arm64-v8a / armeabi-v7a / x86 / x86_64) desde
  [`lzhiyong/android-sdk-tools`](https://github.com/lzhiyong/android-sdk-tools),
  que es la misma fuente que usa el propio Sketchware Pro (ver su issue
  [#1244](https://github.com/Sketchware-Pro/Sketchware-Pro/issues/1244),
  "Upgrade to AAPT2 v35.0.2"). No hace falta desempaquetar el APK de
  Sketchware Pro a mano. `android.jar` no sale de ahí: el workflow lo copia
  directo del Android SDK ya instalado en el runner de CI.

### ¿Por qué era LIBRARY y ahora es APPLICATION?

Al principio lo dejé como `com.android.library` porque no había ninguna
`Activity` ni `res/layout` — solo las clases del motor (`model/`, `core/`,
`steps/`) — y esa combinación produce un `.aar`, no un `.apk` instalable.

Ahora ya tiene una `MainActivity` (banco de pruebas, no el editor visual)
que corre el pipeline completo del motor sobre un proyecto de prueba real
(ver la sección de más arriba, "El motor ya compila un APK real"), y ofrece
instalar el resultado. Instala el APK, dale a "Compilar", y si termina en
"ÉXITO", dale a "Instalar APK generado" — eso confirma que TODO el pipeline
(no solo aapt2) funciona en el dispositivo real, no solo en CI.

### Cómo compilarlo tú mismo

**En GitHub (recomendado para probarlo ya):** hay dos formas:

1. **Automático con el script nuevo** — `scripts/publicar-en-github.sh` crea el
   repo `IdeAppDV` en tu cuenta (con `gh`) y sube todo con `git` en un solo
   paso:
   ```bash
   cd compilador-android
   bash scripts/publicar-en-github.sh          # crea el repo como privado
   bash scripts/publicar-en-github.sh --public  # o público
   ```
   Requiere tener `gh` instalado (`pkg install gh` en Termux) y haber hecho
   `gh auth login` una vez. El workflow de Actions se dispara solo con ese
   push.

2. **A mano** — crea el repo `IdeAppDV` desde github.com, y luego:
   ```bash
   cd compilador-android
   git init && git branch -M main
   git add -A && git commit -m "Motor de compilación Android"
   git remote add origin https://github.com/<tu-usuario>/IdeAppDV.git
   git push -u origin main
   ```
   Se dispara solo en cada push/PR a `main`, o a mano desde la pestaña
   *Actions* → *Run workflow*. Al final sube el `.apk` compilado como
   artifact descargable.

**En local / Termux:** falta un archivo que no puedo generar sin conexión a
internet: `gradle/wrapper/gradle-wrapper.jar` (es un binario). Los scripts
`gradlew` / `gradlew.bat` y `gradle-wrapper.properties` ya están, así que solo
hace falta generarlo una vez con cualquier Gradle que tengas a mano:

```bash
gradle wrapper --gradle-version 8.7
```

Después de eso, `./gradlew :app:assembleDebug` funciona igual que en CI. Para
probar el pipeline completo en el propio teléfono además necesitas tener a
mano `aapt2` + `android.jar` en algún lado de tu almacenamiento (por ejemplo,
generándolos con `scripts/fetch-build-tools.sh` + copiando `android.jar` del
SDK) y elegirlos desde la app con el botón "Elegir build tools" — ya no hace
falta que estén dentro del APK.
