# Data Storage Strategy

## Recommendation
- Use a hybrid storage model:
  - JSON for raw and derived health data.
  - Markdown for durable instructions, schema notes, product decisions, and interpretation guidance for agents.

## Why Not Store Everything in Markdown
- Daily snapshots and long histories are structured time-series data.
- JSON is easier for scripts, dashboards, aggregation, and later migration to SQLite.
- LLM agents can read Markdown well, but they also handle JSON well when the structure is stable.

## What Should Live in JSON
- `health_snapshot.json`: latest daily snapshot.
- `health_history.json`: multi-day history. New syncs merge the currently readable Health Connect window with the private stored history so days outside the latest readable window can remain available.
- Days inside the current readable window are replaced by the fresh Health Connect result so corrected or removed records do not stay stale.
- Previous active/total calorie values are preserved inside the window only when the corresponding optional permission is missing or the optional read fails.
- Daily snapshots include active calories burned as `activeCaloriesKcal` when Health Connect exposes `ActiveCaloriesBurnedRecord`.
- Daily snapshots also include total calories burned as `totalCaloriesKcal` when Health Connect exposes `TotalCaloriesBurnedRecord`; this lets the chart estimate activity calories from `totalCaloriesKcal - bmrKcalPerDay` if active calories are missing and compute `Balance kcal` as total calories minus eaten calories.
- The private export directory is app external files `Documents/exports`.
- On Android 10 or newer, a shared copy is also written to `Downloads/FitnessTracker/exports`; Android 9 keeps only the private copy and share-sheet path.
- Android backup and device-transfer extraction are disabled for app data.
- Future option: `health_weekly_summary.json` or SQLite if the history grows.

## What Should Live in Markdown
- Project conventions and commands.
- Feature decisions and roadmap.
- Data contract explanations.
- Visualization rationale.
- Future analysis templates such as weekly review prompts.

## Agent-Friendly Layout
- `AGENTS.md`: short operational guide for Codex.
- `CLAUDE.md`: Claude Code project memory that imports the relevant docs.
- `docs/*.md`: detailed but stable reference material.

## Future Additions
- `docs/metrics-glossary.md` for field definitions and units.
- `docs/analysis-prompts.md` for reusable prompts over the exported data.
- `docs/change-log.md` for meaningful product changes if the app grows.
