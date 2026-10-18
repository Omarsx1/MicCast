# MicCast macOS Companion (Receptor y Micrófono Virtual)

Aplicación nativa de barra de menú para macOS (Swift / SwiftUI + CoreAudio + Network.framework) que recibe el flujo de audio de ultrabaja latencia desde Android y lo entrega como un micrófono virtual en el sistema.

---

## 1. Requisitos Previos e Instalación de BlackHole

Para que macOS exponga el audio como un micrófono seleccionable en aplicaciones (Zoom, OBS, Discord, GarageBand), se utiliza **BlackHole 2ch** (puente CoreAudio virtual de código abierto y cero latencia).

### Instalación vía Homebrew (Recomendado):
```bash
brew install blackhole-2ch
```

*O bien descarga el instalador `.pkg` oficial desde: https://existential.audio/blackhole/*

> **Nota:** BlackHole se instala directamente sin requerir deshabilitar SIP ni extensiones de kernel peligrosas.

---

## 2. Compilación y Ejecución en macOS

### Opción A: Compilar y ejecutar desde Terminal con Swift (Rápido)
```bash
cd macos
swift run
```

### Opción B: Abrir con Xcode
```bash
cd macos
open Package.swift
```
1. Selecciona el esquema **MicCastMac** y presiona `Cmd + R` para compilar y ejecutar.
2. Aparecerá el icono de micrófono en la barra de menú superior de tu Mac.

---

## 3. Emparejamiento con el Teléfono Android

1. **Abre MicCast en tu Mac:** Haz clic en el icono de micrófono de la barra de menú. Verás un PIN de 6 dígitos (ej: `839201`).
2. **Abre MicCast en tu teléfono Android:**
   - En la pestaña **Wi-Fi (mDNS)** verás aparecer automáticamente tu Mac.
   - Presiona **"Emparejar"**, escribe los 6 dígitos del PIN y confirma.
3. El estado cambiará a **"TRANSMITIENDO EN VIVO"** y verás el vúmetro moverse en tiempo real en ambos dispositivos.

---

## 4. Configuración en tus Aplicaciones

En cualquier aplicación donde quieras usar el micrófono de tu teléfono:
- **Zoom / Google Meet:** *Preferencias de Audio -> Micrófono: "BlackHole 2ch"*.
- **OBS Studio:** *Ajustes -> Audio -> Dispositivo de audio Mic/Auxiliar: "BlackHole 2ch"*.
- **Discord:** *Voz y Vídeo -> Dispositivo de entrada: "BlackHole 2ch"*.
- **GarageBand / Logic Pro:** *Preferencias -> Audio -> Dispositivo de entrada: "BlackHole 2ch"*.

---

## 5. Modo USB (Sub-15ms de Latencia)

Si estás en un entorno con Wi-Fi inestable o requieres la menor latencia posible:
1. Conecta tu teléfono Android por cable USB a la Mac con **Depuración USB** activada.
2. Ejecuta en la Terminal de la Mac:
```bash
adb reverse tcp:51516 tcp:51516 && adb reverse tcp:51517 tcp:51517
```
3. En la app Android, ve a la pestaña **Cable USB** y pulsa **"Conectar vía USB (127.0.0.1)"**.
