# :app (Aplicación de ejemplo / referencia de app host)

Módulo Gradle: `:app`

Este módulo es una aplicación de ejemplo con Jetpack Compose. También sirve como referencia de cómo cablear los módulos del repositorio a través de fronteras (UI/componentes Android vs librerías).

## Qué obtienes (efectos)

Al ejecutar la app en un dispositivo y presionar `Start`:

- El servicio en primer plano (`:core`) empieza a recolectar GPS (`:gps`) y a persistir en Room vía `:storageservice`.
- (Opcional) Dead Reckoning (`:deadreckoning`) produce muestras adicionales.
- (Opcional) corrección EKF asistida por IMU (`ImsEkf`) produce muestras corregidas `gps_corrected`.
- Puedes configurar export/upload (`:datamanager`) para nightly (nocturno) y realtime (tiempo real).

## Cableado de fronteras en esta app

- `:core` (`GeoLocationService`): manifest + permisos, start/stop desde UI, settings vía `SettingsRepository`.
- `:datamanager`: registrar token provider en `DriveTokenProviderRegistry`, iniciar scheduler/manager, editar `DrivePrefsRepository` y `UploadPrefsRepository`.
- `:storageservice`: usar `StorageService` (sin DAOs directos).

## Requisitos

- Android Studio (o Gradle) con JDK 17.
- `secrets.properties` en la raíz (no se versiona). Usa `local.default.properties` como plantilla.
- (Opcional) Google Maps API key para la pantalla Map (`GOOGLE_MAPS_API_KEY`).

## Compilar y ejecutar

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## Cómo usar (acción -> resultado)

1. `Start`: se insertan `LocationSample`.
2. `Save & Apply`: se persisten settings y el servicio se actualiza en vivo.
3. `Map`: ver GPS / GPS(EKF) / Dead Reckoning.
4. `Drive`: sign-in + folder id.
5. `Upload`: habilitar nightly o realtime.

## Ediciones típicas de frontera (cambio -> efecto)

### Cambiar el método de auth usado en uploads en background

Cambio:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

Efecto:

- Trabajo en background de `:datamanager` usa ese provider para tokens.
- Si el provider retorna `null`, el upload se omite de forma segura.

### Habilitar/deshabilitar corrección EKF

Cambio:

- Instalar y configurar EKF al inicio (ver README de `:core`).

Efecto:

- Cuando está habilitado, se persisten muestras `provider="gps_corrected"` y se muestran como `GPS(EKF)` en Map.

### Controlar export/upload en background

Cambio:

- Llamar (o no) a:
  - `MidnightExportScheduler.scheduleNext(context)`
  - `RealtimeUploadManager.start(context)`

Efecto:

- Nightly/realtime solo corren si el entrypoint está iniciado y las preferencias lo permiten.
