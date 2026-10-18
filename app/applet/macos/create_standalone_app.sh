#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$DIR"

echo "=========================================================="
echo "🚀 Generando aplicación MicCast.app para macOS"
echo "=========================================================="

if command -v swift >/dev/null 2>&1; then
    echo "📦 Compilando app nativa en Swift (SwiftUI + CoreAudio)..."
    swift build -c release
    
    APP_BUNDLE="MicCast.app"
    rm -rf "$APP_BUNDLE"
    mkdir -p "$APP_BUNDLE/Contents/MacOS"
    mkdir -p "$APP_BUNDLE/Contents/Resources"
    
    cp .build/release/MicCastMac "$APP_BUNDLE/Contents/MacOS/MicCast"
    chmod +x "$APP_BUNDLE/Contents/MacOS/MicCast"
    
    if [ -f "MicCast/Info.plist" ]; then
        cp MicCast/Info.plist "$APP_BUNDLE/Contents/Info.plist"
    fi
    
    # Firma ad-hoc para macOS
    codesign --force --deep -s - "$APP_BUNDLE" 2>/dev/null || true
    
    echo "🎉 ¡MicCast.app creada con éxito!"
    echo "Moviendo a /Applications..."
    cp -R "$APP_BUNDLE" /Applications/MicCast.app 2>/dev/null || cp -R "$APP_BUNDLE" ~/Applications/MicCast.app 2>/dev/null || true
    
    echo "Iniciando MicCast..."
    open "$APP_BUNDLE"
    echo "=========================================================="
    echo "✅ Ya está abierta en tu barra de menú (junto al reloj)!"
    echo "=========================================================="
else
    echo "⚠️ Swift no detectado. Creando lanzador de doble clic para Python..."
    # Lanzador de un solo clic con AppleScript
    osacompile -e 'do shell script "python3 '"$DIR"'/../scripts/receiver_test.py > /dev/null 2>&1 &"' -o ~/Desktop/MicCast.app
    echo "✅ Lanzador creado en tu Escritorio: MicCast.app"
    open ~/Desktop/MicCast.app
fi
