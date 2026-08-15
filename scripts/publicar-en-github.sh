#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Crea el repositorio "IdeAppDV" en tu cuenta de GitHub y sube este proyecto
# entero (tal cual está en esta carpeta) usando la CLI oficial de GitHub (gh)
# + git normal. Pensado para correr una sola vez, desde Termux o cualquier
# Linux/Mac con gh y git instalados.
#
# Requisitos previos:
#   1) Tener "gh" instalado:
#        - Termux:  pkg install gh
#        - Debian/Ubuntu: https://cli.github.com (repo oficial apt)
#        - Mac: brew install gh
#   2) Haber iniciado sesión una vez: gh auth login
#      (te guía paso a paso, incluye login por navegador aunque estés en
#      Termux sin GUI: elige "Login with a web browser").
#
# Uso:
#   cd compilador-android
#   bash scripts/publicar-en-github.sh
#
# Por defecto crea el repo como PRIVADO. Si lo quieres público, pasa --public:
#   bash scripts/publicar-en-github.sh --public
# -----------------------------------------------------------------------------
set -euo pipefail

REPO_NAME="IdeAppDV"
VISIBILIDAD="--private"

for arg in "$@"; do
    case "$arg" in
        --public)  VISIBILIDAD="--public" ;;
        --private) VISIBILIDAD="--private" ;;
        *) echo "Argumento desconocido: $arg (usa --public o --private)" >&2; exit 1 ;;
    esac
done

# --- Comprobaciones previas ---------------------------------------------
if ! command -v git >/dev/null 2>&1; then
    echo "ERROR: no se encontró 'git'. Instálalo primero (pkg install git en Termux)." >&2
    exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
    echo "ERROR: no se encontró 'gh' (GitHub CLI)." >&2
    echo "  Termux:          pkg install gh" >&2
    echo "  Debian/Ubuntu:   https://cli.github.com" >&2
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "No has iniciado sesión en gh todavía. Ejecutando 'gh auth login'..."
    gh auth login
fi

# Debe correrse desde la raíz del proyecto (donde está este mismo scripts/)
if [ ! -f "scripts/publicar-en-github.sh" ]; then
    echo "ERROR: corre este script desde la raíz de compilador-android/, ej.:" >&2
    echo "       cd compilador-android && bash scripts/publicar-en-github.sh" >&2
    exit 1
fi

# --- Inicializar git localmente si hace falta ----------------------------
if [ ! -d ".git" ]; then
    echo "==> Inicializando repositorio git local..."
    git init
    git branch -M main
fi

# .gitignore mínimo por si no existe, para no subir carpetas de build
if [ ! -f ".gitignore" ]; then
    cat > .gitignore <<'EOF'
.gradle/
build/
app/build/
*.iml
.idea/
local.properties
.DS_Store
EOF
    echo "==> Creado .gitignore básico."
fi

echo "==> Añadiendo archivos..."
git add -A

if ! git diff --cached --quiet; then
    git commit -m "Motor de compilación Android: proyecto Gradle + CI + resolución de aapt2"
else
    echo "==> No hay cambios nuevos que commitear (todo ya estaba commiteado)."
fi

# --- Crear el repo remoto en GitHub (si no existe) y subir --------------
GH_USER="$(gh api user --jq .login)"

if gh repo view "${GH_USER}/${REPO_NAME}" >/dev/null 2>&1; then
    echo "==> El repo ${GH_USER}/${REPO_NAME} ya existe en GitHub, solo hago push."
    if ! git remote get-url origin >/dev/null 2>&1; then
        git remote add origin "https://github.com/${GH_USER}/${REPO_NAME}.git"
    fi
    git push -u origin main
else
    echo "==> Creando repo ${GH_USER}/${REPO_NAME} (${VISIBILIDAD#--}) y subiendo..."
    gh repo create "${REPO_NAME}" ${VISIBILIDAD} --source=. --remote=origin --push
fi

echo
echo "==> Listo. Repo: https://github.com/${GH_USER}/${REPO_NAME}"
echo "==> El workflow de GitHub Actions (.github/workflows/build.yml) debería"
echo "    dispararse solo con este push. Revísalo en la pestaña Actions."
