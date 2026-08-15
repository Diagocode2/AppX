#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Resuelve el punto 1 del README: aapt2 es el único binario nativo que el
# motor de compilación necesita EN EL TELÉFONO y no tiene reimplementación en
# Java/Kotlin. No hace falta sacarlo a mano de la APK de Sketchware Pro: el
# propio equipo de Sketchware Pro usa binarios de aapt2 pre-compilados para
# Android publicados en el repo lzhiyong/android-sdk-tools (ver
# Sketchware-Pro/Sketchware-Pro issue #1244, "Upgrade to AAPT2 v35.0.2", que
# apunta exactamente a ese repo).
#
# OJO con el naming: ese repo NO publica un binario aapt2 suelto por ABI.
# Publica un .zip por arquitectura (ej. android-sdk-tools-aarch64.zip) con
# TODO el toolchain adentro (aapt2, aidl, adb, zipalign...), y usa nombres de
# arquitectura de compilador (aarch64/arm/x86_64/x86), no los nombres de ABI
# de Android (arm64-v8a/armeabi-v7a/x86_64/x86). Este script hace esa
# traducción, descarga el zip que corresponda, y extrae solo el binario
# aapt2 de adentro.
#
# android.jar NO sale de aquí: la tarea "extraerAndroidJar" del
# build.gradle.kts lo toma directo de android.bootClasspath (el compileSdk
# que este módulo YA necesita para compilarse a sí mismo), sin descargar
# nada aparte.
#
# ACTUALIZADO: el destino ahora es app/src/main/jniLibs/<abi>/, NO
# assets/build-tools/<abi>/. aapt2 es un ejecutable, no una librería que se
# lea con InputStream, así que en Android 10+ SOLO puede correr si se
# extrae desde jniLibs a nativeLibraryDir al instalar el APK — un archivo
# copiado a mano a filesDir/ (como se hacía antes desde assets/ o desde un
# árbol SAF) da "Permission denied (error=13)" sin excepción, por más
# chmod +x que se le ponga (ver el comentario grande en BuildTools.kt).
# Por eso el binario también se renombra a "libaapt2.so": AGP solo empaqueta
# y extrae archivos con ese patrón de nombre dentro de jniLibs/.
#
# Uso:
#   scripts/fetch-build-tools.sh <carpeta-destino>
#   (normalmente: app/src/main/jniLibs)
# -----------------------------------------------------------------------------
set -uo pipefail
# OJO: a propósito NO uso "set -e" en todo el script. Con -e, cualquier
# comando que falle (incluida una descarga que da 403/404) mata el script
# INMEDIATAMENTE, incluso dentro de una asignación tipo VAR="$(cmd)", antes de
# que mi propio código de manejo de errores llegue a imprimir nada. Prefiero
# comprobar el código de salida de cada paso a mano y decidir yo qué hacer.

DEST="${1:?Uso: fetch-build-tools.sh <carpeta destino, ej. app/src/main/jniLibs>}"
REPO="lzhiyong/android-sdk-tools"
API="https://api.github.com/repos/${REPO}/releases/latest"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# Traducción ABI de Android -> nombre de arquitectura que usa este repo.
declare -A ARCH_DE_ABI=(
    [arm64-v8a]="aarch64"
    [armeabi-v7a]="arm"
    [x86_64]="x86_64"
    [x86]="x86"
)

# Si hay GITHUB_TOKEN en el entorno (lo pone solo el propio workflow),
# autenticamos: sin token, la API de GitHub limita a 60 peticiones/hora POR
# IP, y los runners de GitHub Actions comparten IP entre miles de jobs a la
# vez -> 403 casi garantizado.
CURL_AUTH_ARGS=()
if [ -n "${GITHUB_TOKEN:-}" ]; then
    CURL_AUTH_ARGS=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

echo "==> Consultando la última release de ${REPO}..."
set +e
HTTP_CODE="$(curl -sSL "${CURL_AUTH_ARGS[@]}" -H "Accept: application/vnd.github+json" \
    -w '%{http_code}' -o "${WORKDIR}/release.json" "$API")"
CURL_EXIT=$?
set -e

if [ "$CURL_EXIT" -ne 0 ]; then
    echo "ERROR: curl falló con código de salida ${CURL_EXIT} al consultar ${API}" >&2
    echo "       (esto es un fallo de red/conexión, no un código HTTP de error)." >&2
    exit 1
fi

if [ "$HTTP_CODE" != "200" ]; then
    echo "ERROR: la API de GitHub devolvió HTTP ${HTTP_CODE} al consultar ${API}" >&2
    echo "Contenido de la respuesta:" >&2
    cat "${WORKDIR}/release.json" >&2 2>/dev/null || true
    if [ "$HTTP_CODE" = "403" ]; then
        echo >&2
        echo "Esto suele ser rate-limit sin autenticar. Comprueba que el paso" >&2
        echo "del workflow exporta GITHUB_TOKEN a este script (ver build.yml)." >&2
    fi
    exit 1
fi

ASSETS_JSON="$(cat "${WORKDIR}/release.json")"

# Lista "nombre<TAB>url" de cada asset de la release. jq si está disponible
# (más fiable con JSON real), si no, un grep/sed de respaldo.
if command -v jq >/dev/null 2>&1; then
    ASSET_LINES="$(printf '%s' "$ASSETS_JSON" | jq -r '.assets[] | "\(.name)\t\(.browser_download_url)"')"
else
    ASSET_LINES="$(printf '%s' "$ASSETS_JSON" | python3 -c '
import json, sys
data = json.load(sys.stdin)
for a in data.get("assets", []):
    print(f"{a[\"name\"]}\t{a[\"browser_download_url\"]}")
')"
fi

if [ -z "$ASSET_LINES" ]; then
    echo "ERROR: la release no tiene assets, o no se pudo parsear la respuesta JSON." >&2
    echo "Respuesta cruda guardada en: ${WORKDIR}/release.json (revísala si el job falla)." >&2
    exit 1
fi

echo "==> Assets disponibles en la última release:"
printf '%s\n' "$ASSET_LINES" | cut -f1 | sed 's/^/    - /'

encontrado_alguno=false

for abi in "${!ARCH_DE_ABI[@]}"; do
    arch="${ARCH_DE_ABI[$abi]}"
    mkdir -p "${DEST}/${abi}"

    # Busca, entre los assets, el que mencione esta arquitectura. El límite
    # de cierre NO incluye "_" (solo "-", "." o fin de línea): así "x86" no
    # hace falso match con "x86_64" (que tiene "_64" pegado justo después).
    linea="$(printf '%s\n' "$ASSET_LINES" | grep -iE "(^|[-_])${arch}([-.]|$)" | head -n1 || true)"

    if [ -z "$linea" ]; then
        echo "AVISO: no se encontró ningún asset para ${abi} (arch '${arch}') en la última release."
        echo "       Revisa manualmente: https://github.com/${REPO}/releases"
        continue
    fi

    nombre_asset="$(printf '%s' "$linea" | cut -f1)"
    url="$(printf '%s' "$linea" | cut -f2)"

    echo "==> Descargando ${nombre_asset} (${abi}) desde ${url}"
    destino_descarga="${WORKDIR}/${nombre_asset}"
    set +e
    curl -sSL "${CURL_AUTH_ARGS[@]}" "$url" -o "$destino_descarga"
    dl_exit=$?
    set -e
    if [ "$dl_exit" -ne 0 ]; then
        echo "AVISO: falló la descarga de ${nombre_asset} (curl exit ${dl_exit}), sigo con las demás ABIs." >&2
        continue
    fi

    # El asset puede ser un .zip con todo el toolchain adentro, o (en
    # releases futuras) quizás ya sea el binario aapt2 suelto. Se manejan
    # los dos casos.
    aapt2_encontrado=""
    case "$nombre_asset" in
        *.zip)
            extract_dir="${WORKDIR}/extract-${abi}"
            mkdir -p "$extract_dir"
            if unzip -oq "$destino_descarga" -d "$extract_dir"; then
                aapt2_encontrado="$(find "$extract_dir" -type f \( -name 'aapt2' -o -name 'aapt2.exe' \) | head -n1 || true)"
            else
                echo "AVISO: no se pudo descomprimir ${nombre_asset}." >&2
            fi
            ;;
        *)
            # Asumimos que ya es el binario directamente.
            aapt2_encontrado="$destino_descarga"
            ;;
    esac

    if [ -z "$aapt2_encontrado" ]; then
        echo "AVISO: no se encontró un binario 'aapt2' dentro de ${nombre_asset} para ${abi}." >&2
        continue
    fi

    # Nombre "libaapt2.so" a propósito (ver cabecera del script): así AGP lo
    # reconoce como librería nativa dentro de jniLibs/ y lo empaqueta +
    # extrae a nativeLibraryDir al instalar, que es el único lugar del
    # sandbox donde Android 10+ permite ejecutarlo.
    cp "$aapt2_encontrado" "${DEST}/${abi}/libaapt2.so"
    chmod 755 "${DEST}/${abi}/libaapt2.so"
    echo "==> aapt2 (${abi}) listo en ${DEST}/${abi}/libaapt2.so"
    encontrado_alguno=true
done

if [ "$encontrado_alguno" = false ]; then
    echo "ERROR: no se pudo resolver aapt2 para ninguna ABI. Revisa el nombre" >&2
    echo "       exacto de los assets impreso arriba y ajusta el filtro de" >&2
    echo "       este script si cambió el naming." >&2
    exit 1
fi

echo "==> aapt2 resuelto y empaquetado en jniLibs. android.jar lo resuelve solo Gradle (extraerAndroidJar)."
