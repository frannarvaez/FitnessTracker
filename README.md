# FitnessTracker

App Android nativa en Kotlin para leer datos de Health Connect y exportarlos a JSON diario.

## Que hace

- Solicita permisos de lectura de Health Connect para peso, grasa corporal, BMR, calorias activas, calorias totales, nutricion, sueno, ejercicio, pasos e historico.
- Lee los datos desde una unica fuente: Health Connect.
- Normaliza el historico a snapshots diarios.
- Exporta:
  - `health_snapshot.json`
  - `health_history.json`
- Mantiene una copia privada dentro de la app y, en Android 10 o superior, tambien copia los JSON a `Downloads/FitnessTracker/exports`.
- En Android 9 la copia compartida en Downloads se omite; la copia privada y la opcion de compartir siguen siendo la ruta principal.
- Fusiona cada nueva sincronizacion con el historico privado:
  - los dias fuera de la ventana leida se conservan
  - los dias dentro de la ventana se reemplazan por la lectura fresca de Health Connect
  - calorias activas/totales previas solo se conservan si falta el permiso o falla esa lectura opcional
- Incluye una pantalla de graficas con:
  - un grafico diario combinado
  - vista inicial de los ultimos 14 dias con desplazamiento horizontal
  - peso, BMR, actividad kcal, calorias comidas, balance kcal, pasos y macros
  - correlaciones exploratorias rapidas

## Donde esta el proyecto Android

El proyecto Gradle nativo esta en [`android`](./android).

## Build

Desde la carpeta `android`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
```

APK generado:

`%LOCALAPPDATA%\FitnessTrackerBuild\app\outputs\apk\debug\app-debug.apk`

## Verificacion

Desde la carpeta `android`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

## Export JSON

La app guarda siempre los ficheros en el directorio privado externo de la app:

`Documents/exports`

En Android 10 o superior tambien exporta una copia compartida accesible desde el movil:

`Downloads/FitnessTracker/exports`

Las copias de seguridad automaticas de Android estan desactivadas y las reglas de backup/extraccion excluyen los datos de la app.

Tambien permite compartir los JSON generados desde la propia app usando la hoja de compartir de Android.

## Documentacion para agentes

- `AGENTS.md`: instrucciones cortas para Codex.
- `CLAUDE.md`: memoria de proyecto para Claude Code.
- `docs/`: contexto duradero del producto, almacenamiento y visualizacion.

## Siguiente paso logico

- Probar en un dispositivo Android con Health Connect instalado.
- Validar que las fuentes (`DataOrigin`) llegan como se espera.
- Anadir automatizacion de import o `adb pull` para traer los JSON al repo local.
- Revisar upgrades de dependencias cuando convenga; `lintDebug` actualmente solo deja avisos de versiones disponibles.
