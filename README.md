# MicCast

[![Build](https://github.com/Omarsx1/MicCast/actions/workflows/build.yml/badge.svg)](https://github.com/Omarsx1/MicCast/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Omarsx1/MicCast)](https://github.com/Omarsx1/MicCast/releases)

Micrófono inalámbrico (Wi-Fi) y por cable (USB) para macOS, con el teléfono Android como
fuente de audio. La Mac recibe el flujo, lo entrega a [BlackHole](https://existential.audio/blackhole/)
y lo expone al sistema como micrófono virtual, utilizable en Discord, Zoom, OBS, Teams o
cualquier aplicación que permita elegir un dispositivo de entrada de CoreAudio.

---

## Cómo funciona

```
┌──────────────┐   TCP 51516 (control, PIN + token)   ┌────────────────────┐
│   Android    │ ───────────────────────────────────► │   macOS (Swift)    │
│  AudioRecord │                                      │  ControlServer     │
│  48 kHz mono │   UDP 51515 (audio PCM 16-bit)       │  AudioReceiver     │
│              │ ───────────────────────────────────► │  VirtualAudioOutput│
└──────────────┘                                      └─────────┬──────────┘
                                                                │ CoreAudio
                                                                ▼
                                                          BlackHole 2ch
                                                          (micrófono virtual)
```

| Elemento | Detalle |
|---|---|
| Descubrimiento | Bonjour/mDNS (`_miccast._tcp`, `_miccast._udp`) |
| Control | TCP 51516, handshake con nonce + HMAC-SHA256 |
| Audio | UDP 51515, PCM 16-bit mono 48 kHz en tramas de 5 ms (240 muestras) |
| USB | TCP 51517 mediante `adb reverse` |
| Autenticación | PIN de 6 dígitos la primera vez; después token de 32 bytes por dispositivo |

---

## Características

- **Audio de baja latencia**: tramas de 5 ms, buffer de recepción acotado a 40 ms y buffer
  de hardware de 64 muestras (1.33 ms).
- **Emparejamiento persistente**: el PIN solo se introduce una vez. La Mac emite un token
  por teléfono y las siguientes conexiones son de un toque.
- **Revocación por dispositivo**: lista de dispositivos emparejados en el popover, con
  revocación individual.
- **Protección contra fuerza bruta**: 5 intentos fallidos bloquean el emparejamiento 60 s,
  y el bloqueo sobrevive al reinicio de la app.
- **Protocolo versionado**: los clientes v0x02 (solo PIN) siguen funcionando junto a los
  v0x03 (tokens).
- **Selección automática de BlackHole** y detección de cambios de dispositivo en caliente.

---

## Requisitos

| Componente | Requisito |
|---|---|
| macOS | 13.0 o superior (Apple Silicon o Intel) |
| Android | 8.0 (API 26) o superior |
| Mac | [BlackHole 2ch](https://existential.audio/blackhole/) — `brew install blackhole-2ch` |
| Red | Misma red Wi-Fi (o cable USB con depuración ADB) |

Si BlackHole está instalado pero no aparece como dispositivo, recarga el driver:

```bash
sudo killall coreaudiod
```

---

## Instalación

### macOS

1. Descarga `MicCast-<versión>.dmg` desde [Releases](../../releases).
2. Ábrelo y arrastra `MicCast.app` a **Aplicaciones**.
3. La app está firmada ad-hoc (sin notarización de Apple). En el primer arranque usa
   clic derecho → **Abrir**. Si macOS la marca como dañada:

   ```bash
   xattr -dr com.apple.quarantine /Applications/MicCast.app
   ```

4. Acepta el permiso de **red local** (necesario para mDNS).
5. El icono aparece en la barra de menús; la app no muestra icono en el Dock.

### Android

1. Descarga `MicCast-Android-<versión>.apk` desde [Releases](../../releases) e instálalo.
2. Concede el permiso de **micrófono** y, en Android 13+, el de **notificaciones**
   (el servicio de captura es en primer plano).

---

## Uso

1. En la Mac, abre el popover de MicCast y copia el PIN de 6 dígitos.
2. En Android, toca tu Mac en la lista e introduce el PIN.
3. En la aplicación de destino (Discord, Zoom, OBS…), selecciona **`BlackHole 2ch`** como
   micrófono.
4. El medidor `NIVEL DE ENTRADA` del popover confirma que el audio está llegando.

El PIN cambia en cada arranque de la app en macOS; el token no. Tras el primer
emparejamiento, las reconexiones no piden PIN.

### Revocar un dispositivo

`Opciones` → `DISPOSITIVOS EMPAREJADOS` → icono de papelera. El teléfono borra su token
en el siguiente rechazo y vuelve a pedir PIN.

---

## Compatibilidad con aplicaciones

- **Funcionan** las apps que enumeran dispositivos vía CoreAudio y permiten elegir la
  entrada: Discord, Zoom, OBS, Teams, Audacity, GarageBand.
- **WhatsApp Desktop** no es fiable con micrófonos virtuales: su selector se acopla al
  perfil Bluetooth HFP/A2DP, y al dejar de usar el auricular como entrada, el sistema lo
  pasa a A2DP (sin micrófono) y la sesión de audio de la llamada falla. Para WhatsApp usa
  el micrófono físico.

---

## Seguridad

- El PIN es efímero (se regenera en cada arranque) y nunca se escribe en disco.
- El token se guarda en macOS en `~/Library/Application Support/MicCast/paired-devices.json`
  con permisos `0600`; en Android se cifra con AES-256-GCM y una clave que no sale del
  Android Keystore.
- El handshake usa nonce de 16 bytes + HMAC-SHA256 con comparación en tiempo constante.
- Los intentos fallidos y el bloqueo se persisten, por lo que reiniciar la app no los
  reinicia.
- El tráfico de audio **no va cifrado** (UDP en LAN). No lo uses en redes no confiables.

---

## Desarrollo

### macOS

Requiere Command Line Tools (no hace falta Xcode completo).

```bash
cd app/applet/macos
swift run                        # desarrollo
bash build_app.sh                # MicCast.app local
bash package_release.sh 1.1.3    # DMG universal (arm64 + x86_64) en dist/
```

La app es un `MenuBarExtra` de SwiftUI (`LSUIElement`). El motor de audio es
`AVAudioEngine` con un `AVAudioSourceNode` alimentado por un ring buffer; el dispositivo de
salida **debe** seleccionarse antes de `connect()`, porque el grafo queda ligado al
hardware en ese momento.

### Android

Requiere JDK 21 y Gradle 9.3.1 (o Android Studio). El repositorio no incluye `gradlew`.

```bash
gradle :app:assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

El servicio de captura es un `ForegroundService` con `AudioRecord` (VOICE_COMMUNICATION
como fuente preferida) y tramas de 5 ms.

---

## Publicación

El workflow [`.github/workflows/build.yml`](.github/workflows/build.yml) compila ambos
artefactos:

- **Manual**: Actions → *Build artifacts* → *Run workflow*. Los binarios quedan como
  artifacts descargables.
- **Release**: publica un tag `vX.Y.Z`.

  ```bash
  git tag v1.1.3
  git push origin v1.1.3
  ```

  El workflow compila el APK y el DMG y crea la release automáticamente.

El APK se firma con un keystore de debug almacenado en el secret
`DEBUG_KEYSTORE_BASE64`, para que todas las versiones compartan firma y las
actualizaciones se instalen sobre la anterior.

---

## Estructura del repositorio

```
.
├── app/                          # Módulo Android (Kotlin + Compose)
│   ├── src/main/java/com/example/
│   │   ├── audio/                # Captura con AudioRecord
│   │   ├── network/              # ControlClient, AudioStreamer, PairingStore
│   │   ├── protocol/             # Cabecera binaria y handshake v0x03
│   │   ├── service/              # ForegroundService de streaming
│   │   └── ui/                   # Compose + ViewModel
│   └── applet/                   # Receptor para macOS
│       ├── macos/                # Swift Package + scripts de empaquetado
│       └── scripts/              # Receptor de prueba en Python
├── odd/tasks/                    # Especificaciones de features implementadas
└── .github/workflows/            # CI: APK + DMG + release
```

---

## Limitaciones conocidas y trabajo pendiente

| # | Estado | Descripción |
|---|---|---|
| 1 | **Pendiente** | **Detección de caída del control en Android.** Si la app de macOS se cierra o reinicia, el servicio sigue mostrando "Conectado" y transmite con una sesión muerta; hay que pulsar *Detener* o forzar el cierre. |
| 2 | **Pendiente** | **Token indexado por host.** En Android el token se guarda por IP; si el router le asigna otra IP a la Mac, el teléfono no encuentra su token y vuelve a pedir PIN. Debería indexarse por nombre Bonjour o por un ID estable del servidor. |
| 3 | **Pendiente** | **Tests en CI.** El workflow solo ejecuta `assembleDebug`. Existe un test unitario del frame de handshake (`AudioProtocolTest`) que no se ejecuta; conviene añadir `testDebugUnitTest`. |
| 4 | **Pendiente** | **Firma y notarización de macOS.** El DMG es ad-hoc; sin un Developer ID los usuarios deben saltarse Gatekeeper manualmente. |
| 5 | **Pendiente** | **Firma de release en Android.** El APK es de debug; para Play Store hace falta un keystore de subida y `assembleRelease`. |
| 6 | **Pendiente** | **Arranque automático en macOS.** La app no se registra como login item; tras reiniciar hay que abrirla a mano. |
| 7 | **Pendiente** | **Opus.** El protocolo reserva el flag y la UI expone el enum, pero solo PCM 16-bit está implementado; no hay codificador Opus. |
| 8 | **Pendiente** | **USB automático.** La conexión por cable requiere ejecutar `adb reverse tcp:51516` y `tcp:51517` manualmente. |
| 9 | **Pendiente** | **Licencia.** El repositorio no define una licencia; sin ella no hay términos claros de uso ni contribución. |

---

## Créditos

- [BlackHole](https://existential.audio/blackhole/) por Existential Audio, el puente de
  audio virtual que hace posible la entrega al sistema.
- Proyecto original generado en Google AI Studio.
