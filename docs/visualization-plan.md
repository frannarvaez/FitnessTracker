# Visualization Plan

## Current Implementation
- The history dashboard uses one combined daily chart.
- The chart is horizontally scrollable and opens on the latest 14 days.
- The x-axis range is fixed to the exported daily history.
- Each series only plots points for days where that metric has data.
- Weight is always included when available.
- Toggleable series include body fat, BMR, activity calories, calories eaten, total-minus-eaten calorie balance, steps, sleep, exercise minutes, protein, carbs, and fat.
- Total calories remain in JSON and analyzer code for balance/fallback calculations, but are not exposed as a graph toggle.
- The chart includes a color legend for every visible line.
- Tapping a chart day shows a marker badge with the date and raw values for visible series.
- A summary card shows burned-minus-consumed calories over the latest 7 and 30 calendar days ending at the newest exported date.
- The Y-axis is a real weight axis in kg when weight is available. It uses the actual exported weight range with adaptive padding instead of forcing 50-100 kg.
- If no weight exists, the chart uses a normalized 0-100 visual axis so non-weight data can still be inspected.
- Weight is plotted as raw kg. Calories, steps, and macros are re-scaled visually into the weight-axis range so they can move visibly in the same diagram.
- Text still shows lightweight exploratory correlations.

## Data Interpretation
- `activeCaloriesKcal` comes from Health Connect `ActiveCaloriesBurnedRecord` and is shown as `Actividad`.
- `totalCaloriesKcal` comes from Health Connect `TotalCaloriesBurnedRecord` and is kept for `Balance kcal` and fallback calculations.
- Calories eaten come from `NutritionRecord.ENERGY_TOTAL`.
- `Balance kcal` is `totalCaloriesKcal` minus `nutrition.caloriesKcal`; if total calories are missing, it can fall back to `bmrKcalPerDay + activeCaloriesKcal - nutrition.caloriesKcal`.
- The 7-day and 30-day summaries sum that same daily balance only for days where burned and eaten calories are both available, and show the number of complete days used.
- Activity calories prefer `activeCaloriesKcal`; if it is unavailable, the chart estimates them as `totalCaloriesKcal - bmrKcalPerDay`.
- Positive `Balance kcal` means total calories burned exceeded calories eaten for that day.

## Interaction Model
- Top controls: filter chips for visible series.
- Main body: one combined line chart with visible data points.
- The chart starts at the latest 14-day window and can be dragged horizontally to inspect older days.

## Future Additions
1. Add optional date-range filters.
2. Add lagged-correlation views for more meaningful weight analysis.
