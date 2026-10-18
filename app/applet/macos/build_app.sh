#!/bin/bash
set -e

echo "🔨 Compilando MicCast para macOS..."

# Compilar binario nativo en modo Release optimizado para Apple Silicon e Intel
swift build -c release

# Crear estructura del paquete .app de macOS
APP_NAME="MicCast"
BUNDLE_DIR="$APP_NAME.app"

rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR/Contents/MacOS"
mkdir -p "$BUNDLE_DIR/Contents/Resources"

# Copiar el binario compilado
cp .build/release/MicCastMac "$BUNDLE_DIR/Contents/MacOS/$APP_NAME"
chmod +x "$BUNDLE_DIR/Contents/MacOS/$APP_NAME"

# Copiar Info.plist
cp MicCast/Info.plist "$BUNDLE_DIR/Contents/Info.plist"

# Firmar localmente (ad-hoc) para que macOS lo abra con un solo clic sin bloqueos de Gatekeeper
codesign --force --deep -s - "$BUNDLE_DIR"

echo "✅ ¡Listo! Se ha creado '$BUNDLE_DIR' con éxito."
echo "👉 Puedes abrirlo con doble clic en el Finder o arrastrarlo a tu carpeta Aplicaciones."
