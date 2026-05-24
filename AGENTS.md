# FitnessTracker Agent Notes

## Goal
- Android app that reads Health Connect as the only health data source.
- Export daily snapshots for local analysis by coding agents.
- Keep the raw health data in machine-friendly formats and the project context in Markdown.

## Repo Map
- `android/`: native Android app.
- `docs/`: durable project context for Codex and Claude Code.
- `README.md`: quickstart for humans.

## Key Commands
- Build debug APK:
```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
```
- Run unit tests:
```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat testDebugUnitTest
```
- Run Android lint:
```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat lintDebug
```

## Current Product Decisions
- Package/application id: `com.snabbt.fitnesstracker.fitness_tracker`
- Health provider: `Health Connect`
- Public export directory on Android 10+: `Downloads/FitnessTracker/exports`
- Private export directory: app external files `Documents/exports`
- Android cloud/device-transfer backup is disabled for app data.
- Shared export to Downloads is skipped on Android 9; the private export and Android share sheet remain available.
- Current UI supports:
  - checking Health Connect availability
  - requesting permissions
  - requesting historical access when core permissions are already granted and history is supported
  - opening Health Connect settings
  - checking the most recent health data point
  - syncing X days into JSON
  - sharing the latest JSON export via Android share sheet
  - viewing a history dashboard with one combined daily chart
  - opening that chart on the latest 14 days with horizontal scrolling
  - toggling series for BMR, activity calories, eaten calories, total-minus-eaten calorie balance, steps, protein, carbs, and fat
  - showing lightweight exploratory correlations inside the app

## Data Rules
- Keep raw time-series exports in JSON.
- Use Markdown for project memory, architecture, UX decisions, and data contracts.
- Do not replace JSON snapshots with Markdown summaries as the system of record.
- When syncing, preserve stored days outside the current readable window.
- For days inside the current readable window, fresh Health Connect data is authoritative and can clear stale old values.
- Preserve previous active/total calorie values inside the window only when that optional permission is missing or that optional read fails.
- Consume all Health Connect `readRecords` pages for non-aggregate records.

## Read Next
- [docs/index.md](C:/Users/franc/Dev/FitnessTracker/docs/index.md)
