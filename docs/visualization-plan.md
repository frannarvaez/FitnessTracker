# Visualization Plan

## Current Implementation
- The history dashboard uses one combined daily chart.
- The chart is horizontally scrollable and opens on the latest 14 days.
- The x-axis range is fixed to the exported daily history.
- Each series only plots points for days where that metric has data.
- Weight is always included when available.
- Toggleable series include active calories burned, calories eaten, burned-minus-eaten difference, steps, protein, carbs, and fat.
- The Y-axis is a real weight axis in kg, defaulting to 50-100 kg and expanding when weight data falls outside that range.
- Weight is plotted as raw kg. Calories, steps, and macros are re-scaled visually into the weight-axis range so they can move visibly in the same diagram.
- Text still shows lightweight exploratory correlations.

## Data Interpretation
- `activeCaloriesKcal` comes from Health Connect `ActiveCaloriesBurnedRecord`.
- `totalCaloriesKcal` comes from Health Connect `TotalCaloriesBurnedRecord` and is kept as a fallback source.
- Calories eaten come from `NutritionRecord.ENERGY_TOTAL`.
- `Dif kcal` is burned calories minus `nutrition.caloriesKcal`.
- Burned calories prefer `activeCaloriesKcal`; if it is unavailable, the chart estimates active burned calories as `totalCaloriesKcal - bmrKcalPerDay`.
- Positive `Dif kcal` means active calories burned exceeded calories eaten for that day.

## Interaction Model
- Top controls: filter chips for visible series.
- Main body: one combined line chart with visible data points.
- The chart starts at the latest 14-day window and can be dragged horizontally to inspect older days.

## Future Additions
1. Add a marker tooltip with raw values for the touched day.
2. Add a legend that maps line color to metric name.
3. Add optional date-range filters.
4. Add lagged-correlation views for more meaningful weight analysis.
