# Project Context

## Product Goal
- Build a minimal Android app that reads health data from Health Connect and prepares it for later analysis by coding agents.

## Scope
- Read:
  - `WeightRecord`
  - `BodyFatRecord`
  - `BasalMetabolicRateRecord`
  - `ActiveCaloriesBurnedRecord`
  - `TotalCaloriesBurnedRecord`
  - `NutritionRecord`
  - `SleepSessionRecord`
  - `ExerciseSessionRecord`
  - `StepsRecord`
- Normalize to daily snapshots.
- Export `health_snapshot.json` and `health_history.json`.

## Current App Flow
- Check Health Connect status.
- Request permissions.
- Open the Health Connect management screen.
- Inspect the latest available data point by metric.
- Sync a selected range of days. The selector offers 1, 2, 3, and 6 months; 1, 2, and 5 years; and `max`, with 2 months selected by default.
- Keep a private export copy in app external files `Documents/exports`.
- Export JSON to `Downloads/FitnessTracker/exports` on Android 10 or newer.
- On Android 9, skip the shared Downloads copy and keep the private export/share-sheet path.
- Merge each new sync window with the existing private `health_history.json`: preserve days outside the window, replace days inside the window with fresh Health Connect data, and preserve optional active/total calorie values only when their permission is missing or their read fails.
- Share the latest export with Android's share sheet.
- View a metrics and charts dashboard with one combined daily chart.
- The chart opens on the latest 14 days and can be dragged horizontally.
- Weight is always included when available.
- Toggle chart series for active calories, eaten calories, burned-minus-eaten calories, steps, protein, carbs, and fat.
- Show exploratory correlation summaries for quick visual triage before deeper offline analysis.

## Historical Access Rule
- Without `READ_HEALTH_DATA_HISTORY`, only the previous 30 days are available.
- With `READ_HEALTH_DATA_HISTORY`, the app requests full historical access.
- Repeated 30-day syncs preserve older days already stored in the app's private export memory.

## Current Android Notes
- UI stack: XML + ViewBinding.
- Health Connect client: `androidx.health.connect:connect-client:1.1.0`
- Kotlin/JVM target: Java 17.
- Android backup/device-transfer extraction is disabled for app data.
- Unit tests cover the history merge rules.
