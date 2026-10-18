# MicCast

Convierte tu teléfono Android en un micrófono inalámbrico (Wi-Fi) o por cable (USB) de
ultrabaja latencia para macOS. El audio se recibe en la Mac y se entrega a
[BlackHole](https://existential.audio/blackhole/) como micrófono virtual, para usarlo en
Zoom, Discord, OBS, Teams o cualquier app que acepte una entrada de audio.

## Descargas

Los binarios se publican en [Releases](../../releases):

| Archivo | Qué es |
|---|---|
| `MicCast-Android-<versión>.apk` | App Android (Android 8.0+) |
| `MicCast-<versión>.dmg` | App macOS (universal: Apple Silicon + Intel, macOS 13+) |

## Requisitos

- **macOS 13 o superior** en la Mac.
- **Android 8.0 o superior** en el teléfono.
- **BlackHole 2ch** en la Mac: `brew install blackhole-2ch`.
  Si tras instalarlo no aparece como dispositivo, recarga el driver:
  `sudo killall coreaudiod` (o reinicia la Mac).

## Instalación en macOS

1. Descarga el `.dmg` desde Releases y ábrelo.
2. Arrastra `MicCast.app` a la carpeta **Aplicaciones**.
3. La app está firmada ad-hoc (sin notarización de Apple). La primera vez, ábrela con
   clic derecho → **Abrir**. Si macOS la marca como dañada:

   ```bash
   xattr -dr com.apple.quarantine /Applications/MicCast.app
   ```

4. Aparece un icono de micrófono en la barra de menús (no en el Dock: es una app de
   barra de menús).
5. Acepta el permiso de **red local** para que el teléfono la descubra por mDNS.

## Instalación en Android

1. Descarga el `.apk` desde Releases e instálalo (permite "instalar apps desconocidas"
   para tu navegador o gestor de archivos).
2. Concede el permiso de **micrófono**.

## Emparejamiento

1. En la Mac, abre el popover y copia el PIN de 6 dígitos.
2. En Android, toca tu Mac en la lista y escribe el PIN. **Solo la primera vez.**
3. La Mac emite un token por dispositivo, así que las siguientes conexiones se hacen
   con un solo toque, sin PIN.
4. Para revocar un teléfono: `Opciones` → `DISPOSITIVOS EMPAREJADOS` → icono de papelera.
   El teléfono volverá a pedir PIN en el siguiente intento.

El PIN cambia cada vez que se abre la app en la Mac; el token no. Tras 5 intentos
fallidos la Mac bloquea el emparejamiento 60 segundos, y ese bloqueo sobrevive a un
reinicio de la app.

## Uso

1. En la Mac elige `BlackHole 2ch` como entrada en la app de destino (Discord, Zoom...).
2. Conecta desde Android y verifica que el medidor `NIVEL DE ENTRADA` se mueve.
3. Si no hay señal, revisa que `SALIDA VIRTUAL` muestre BlackHole con el check verde.

## Desarrollo

**macOS** (requiere Command Line Tools):

```bash
cd app/applet/macos
swift run                 # ejecutar en desarrollo
bash build_app.sh         # crear MicCast.app
bash package_release.sh 1.1.0   # DMG universal + sha256 en dist/
```

**Android** (Android Studio o Gradle 9.3.1 + JDK 21):

```bash
gradle :app:assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

El proyecto no incluye `gradlew`; usa el Gradle instalado o Android Studio.

## Publicar una release

El workflow `.github/workflows/build.yml` compila el APK y el DMG.

- **Manual:** Actions → *Build artifacts* → *Run workflow* (los binarios quedan como
  artifacts descargables).
- **Release:** publica un tag con formato `vX.Y.Z`:

  ```bash
  git tag v1.1.0
  git push origin v1.1.0
  ```

  El workflow compila ambos artefactos y crea la release automáticamente.

**Nota sobre firma:** el APK se compila en modo *debug* (firmado con un keystore de
debug generado en CI), porque el repositorio no contiene un keystore de subida. Para
publicar en Play Store necesitas tu propio keystore y `assembleRelease`.
