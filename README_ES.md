# GeoLocationProvider (resumen en Español)

GeoLocationProvider es un SDK y una aplicación de ejemplo para Android (multi-módulo) que permite registrar, almacenar, exportar y subir datos de localización.

En resumen:

- guarda muestras de ubicación en una base de datos Room
- (opcional) genera muestras de Dead Reckoning (DR)
- exporta a GeoJSON o GPX (opcionalmente en ZIP)
- sube exportaciones/snapshots a Google Drive (backup nocturno y subida en tiempo real opcional)

Otros idiomas:

- English: `README.md`
- Japanese: `README_JA.md`

## Por dónde empezar

- Ejecutar la app de ejemplo: después de este README, ver `app/README.md`.
- Reutilizar el SDK en tu app: empezar por `core/README.md` y `storageservice/README.md`.
- Exportar/subir a Drive: ver `datamanager/README.md` y uno de los módulos de auth.

## Inicio rápido (app de ejemplo)

Requisitos:

- Android Studio (o Gradle) con JDK 17
- Android SDK instalado
- un archivo local `secrets.properties` en la raíz del repo (no se versiona)
  - usar `local.default.properties` como plantilla

Compilar e instalar:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Flujo básico en la app:

1. Presiona `Start` para iniciar el servicio en primer plano.
2. Ajusta los intervalos GPS/DR y presiona `Save & Apply`.
3. Usa `Map` para visualizar providers (GPS / GPS(EKF) / Dead Reckoning).
4. Usa `Drive` para iniciar sesión y configurar una carpeta.
5. Usa `Upload` para habilitar nocturno o en tiempo real.

## Mapa de documentación

Docs raíz:

- Reglas del repo y responsabilidades por módulo: `AGENTS.md` (también `AGENTS_JA.md`, `AGENTS_ES.md`)
- Resumen: `README.md` (EN), `README_JA.md` (JA), `README_ES.md` (ES)

Docs por módulo (recomendado para "cómo usar a través de fronteras de módulos"):

- `app/README.md` (JP: `app/README_JP.md`, ES: `app/README_ES.md`)
- `core/README.md` (JP: `core/README_JP.md`, ES: `core/README_ES.md`)
- `storageservice/README.md` (JP: `storageservice/README_JP.md`, ES: `storageservice/README_ES.md`)
- `gps/README.md` (JP: `gps/README_JP.md`, ES: `gps/README_ES.md`)
- `deadreckoning/README.md` (JP: `deadreckoning/README_JP.md`, ES: `deadreckoning/README_ES.md`)
- `dataselector/README.md` (JP: `dataselector/README_JP.md`, ES: `dataselector/README_ES.md`)
- `datamanager/README.md` (JP: `datamanager/README_JP.md`, ES: `datamanager/README_ES.md`)
- `auth-credentialmanager/README.md` (JP: `auth-credentialmanager/README_JP.md`, ES: `auth-credentialmanager/README_ES.md`)
- `auth-appauth/README.md` (JP: `auth-appauth/README_JP.md`, ES: `auth-appauth/README_ES.md`)

Nota: los módulos usan `README_JP.md` para japonés, mientras que el resumen japonés en la raíz es `README_JA.md`.

## Arquitectura (vista rápida)

Flujo de datos:

1. `:gps` produce valores `GpsObservation` (actualmente vía `LocationManagerGpsEngine`).
2. `:core` (`GeoLocationService`) convierte observaciones a filas `LocationSample` y las inserta vía `:storageservice`.
3. `:deadreckoning` puede usarse desde `:core` para generar muestras DR adicionales (`provider="dead_reckoning"`).
4. `:datamanager` exporta y sube:
   - worker nocturno: ZIP por día a Downloads y Drive
   - gestor en tiempo real: snapshot en caché y Drive (y borra filas subidas de la BD)
5. Los módulos de auth implementan `GoogleDriveTokenProvider` y se registran vía `DriveTokenProviderRegistry`.

Regla de frontera:

- Solo `:storageservice` toca DAOs de Room directamente.
- Los demás módulos usan `StorageService` y sus contratos documentados (orden, rangos, comportamiento ante errores).

## Archivos de configuración local

- `local.properties` (no se versiona): ruta del SDK de Android y configuración local
- `secrets.properties` (no se versiona): valores del Secrets Gradle Plugin, por ejemplo:

```properties
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

## Build y verificación

```bash
./gradlew test
./gradlew lint
```

## Notas de desarrollo

- El código de producción es ASCII-only; la documentación puede ser multilingüe.
- No hagas commit de secretos (`secrets.properties`, `google-services.json`, etc.).
