package com.snabbt.fitnesstracker.fitness_tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HealthHistoryAnalyzerTest {
    private val analyzer = HealthHistoryAnalyzer()

    @Test
    fun weightAxisUsesActualWeightRangeInsteadOfDefaultBand() {
        val dashboard =
            analyzer.buildDashboard(
                history =
                    listOf(
                        DailyHealthSnapshot(date = "2026-04-01", weightKg = 80.0),
                        DailyHealthSnapshot(date = "2026-04-02", weightKg = 80.3),
                    ),
                selectedMetrics = emptySet(),
            )

        val chart = requireNotNull(dashboard.combinedChart)
        assertTrue(chart.minY < 80.0f)
        assertTrue(chart.maxY > 80.3f)
        assertTrue(chart.maxY - chart.minY <= 2.0f)
    }

    @Test
    fun nutritionSeriesDoNotNeedWeightOnTheSameDay() {
        val dashboard =
            analyzer.buildDashboard(
                history =
                    listOf(
                        DailyHealthSnapshot(date = "2026-04-01", weightKg = 80.0),
                        DailyHealthSnapshot(
                            date = "2026-04-02",
                            nutrition =
                                NutritionSummary(
                                    caloriesKcal = 2100,
                                    proteinG = 130,
                                ),
                        ),
                    ),
                selectedMetrics =
                    setOf(
                        ComparisonMetric.EATEN_CALORIES,
                        ComparisonMetric.PROTEIN,
                    ),
            )

        val chart = requireNotNull(dashboard.combinedChart)
        val labels = chart.lines.map { it.label }
        assertEquals(listOf("Peso", "Comidas", "Proteina"), labels)
        assertEquals(listOf(0f), chart.lines.single { it.label == "Peso" }.xValues)
        assertEquals(listOf(1f), chart.lines.single { it.label == "Comidas" }.xValues)
        assertEquals(listOf(1f), chart.lines.single { it.label == "Proteina" }.xValues)
    }

    @Test
    fun dashboardCanPlotEveryStoredMetricWhenDataExists() {
        val dashboard =
            analyzer.buildDashboard(
                history =
                    listOf(
                        DailyHealthSnapshot(
                            date = "2026-04-01",
                            weightKg = 80.0,
                            bodyFatPct = 18.5,
                            bmrKcalPerDay = 1700,
                            activeCaloriesKcal = 600,
                            totalCaloriesKcal = 2300,
                            steps = 9000,
                            sleepMinutes = 420,
                            nutrition =
                                NutritionSummary(
                                    caloriesKcal = 2100,
                                    proteinG = 130,
                                    carbsG = 220,
                                    fatG = 70,
                                ),
                            exercise =
                                listOf(
                                    ExerciseSessionSummary(
                                        type = "running",
                                        typeCode = 1,
                                        startTime = "2026-04-01T07:00:00Z",
                                        endTime = "2026-04-01T07:30:00Z",
                                        minutes = 30,
                                        sourcePackage = "test",
                                    ),
                                ),
                        ),
                    ),
                selectedMetrics = ComparisonMetric.values().toSet(),
            )

        val labels = requireNotNull(dashboard.combinedChart).lines.map { it.label }
        assertTrue(
            labels.containsAll(
                listOf(
                    "Peso",
                    "Grasa %",
                    "BMR",
                    "Actividad",
                    "Total kcal",
                    "Comidas",
                    "Balance kcal",
                    "Pasos",
                    "Sueno",
                    "Ejercicio",
                    "Proteina",
                    "Carbs",
                    "Grasa",
                ),
            ),
        )
    }

    @Test
    fun userSelectableMetricsHideTotalCaloriesButKeepTheMetricAvailable() {
        assertTrue(ComparisonMetric.TOTAL_CALORIES in ComparisonMetric.values())
        assertTrue(ComparisonMetric.TOTAL_CALORIES !in ComparisonMetric.userSelectableValues())
    }

    @Test
    fun calorieBalanceUsesTotalCaloriesMinusEatenCalories() {
        val dashboard =
            analyzer.buildDashboard(
                history =
                    listOf(
                        DailyHealthSnapshot(
                            date = "2026-04-01",
                            bmrKcalPerDay = 1700,
                            activeCaloriesKcal = 800,
                            totalCaloriesKcal = 2300,
                            nutrition = NutritionSummary(caloriesKcal = 2100),
                        ),
                    ),
                selectedMetrics = setOf(ComparisonMetric.CALORIE_BALANCE),
            )

        val balance = requireNotNull(dashboard.combinedChart).lines.single { it.label == "Balance kcal" }

        assertEquals(listOf(200.0), balance.rawValues)
        assertEquals(listOf("+200 kcal"), balance.formattedValues)
    }

    @Test
    fun calorieBalanceCanFallbackToBmrPlusActivityWhenTotalCaloriesAreMissing() {
        val dashboard =
            analyzer.buildDashboard(
                history =
                    listOf(
                        DailyHealthSnapshot(
                            date = "2026-04-01",
                            bmrKcalPerDay = 1700,
                            activeCaloriesKcal = 600,
                            nutrition = NutritionSummary(caloriesKcal = 2100),
                        ),
                    ),
                selectedMetrics = setOf(ComparisonMetric.CALORIE_BALANCE),
            )

        val balance = requireNotNull(dashboard.combinedChart).lines.single { it.label == "Balance kcal" }

        assertEquals(listOf(200.0), balance.rawValues)
        assertEquals(listOf("+200 kcal"), balance.formattedValues)
    }

    @Test
    fun calorieBalanceSummariesUseLastSevenAndThirtyCalendarDays() {
        val start = LocalDate.parse("2026-04-01")
        val history =
            (0..30).map { day ->
                DailyHealthSnapshot(
                    date = start.plusDays(day.toLong()).toString(),
                    totalCaloriesKcal = 2500,
                    nutrition = NutritionSummary(caloriesKcal = 2100),
                )
            }

        val dashboard = analyzer.buildDashboard(history = history, selectedMetrics = emptySet())

        val week = dashboard.calorieBalanceSummaries.single { it.label == "Ultimos 7 dias" }
        val month = dashboard.calorieBalanceSummaries.single { it.label == "Ultimos 30 dias" }
        assertEquals(2800.0, requireNotNull(week.netKcal), 0.0)
        assertEquals(12000.0, requireNotNull(month.netKcal), 0.0)
        assertEquals(7, week.completeDays)
        assertEquals(30, month.completeDays)
        assertEquals("+2800 kcal", week.valueText)
        assertTrue(week.detailText.contains("2026-04-25 a 2026-05-01"))
        assertTrue(month.detailText.contains("2026-04-02 a 2026-05-01"))
    }

    @Test
    fun calorieBalanceSummariesOnlyCountDaysWithBurnedAndEatenValues() {
        val dashboard =
            analyzer.buildDashboard(
                history =
                    listOf(
                        DailyHealthSnapshot(
                            date = "2026-04-01",
                            totalCaloriesKcal = 5000,
                            nutrition = NutritionSummary(caloriesKcal = 1000),
                        ),
                        DailyHealthSnapshot(
                            date = "2026-04-04",
                            totalCaloriesKcal = 2300,
                            nutrition = NutritionSummary(caloriesKcal = 2100),
                        ),
                        DailyHealthSnapshot(
                            date = "2026-04-05",
                            bmrKcalPerDay = 1700,
                            activeCaloriesKcal = 600,
                            nutrition = NutritionSummary(caloriesKcal = 2100),
                        ),
                        DailyHealthSnapshot(
                            date = "2026-04-06",
                            totalCaloriesKcal = 2400,
                        ),
                        DailyHealthSnapshot(
                            date = "2026-04-07",
                            nutrition = NutritionSummary(caloriesKcal = 2000),
                        ),
                        DailyHealthSnapshot(date = "2026-04-10"),
                    ),
                selectedMetrics = emptySet(),
            )

        val week = dashboard.calorieBalanceSummaries.single { it.label == "Ultimos 7 dias" }
        assertEquals(400.0, requireNotNull(week.netKcal), 0.0)
        assertEquals(4600.0, requireNotNull(week.burnedKcal), 0.0)
        assertEquals(4200.0, requireNotNull(week.eatenKcal), 0.0)
        assertEquals(2, week.completeDays)
        assertEquals(7, week.expectedDays)
        assertEquals("+400 kcal", week.valueText)
        assertTrue(week.detailText.contains("2/7 dias completos"))
    }

    @Test
    fun chartMarkerValuesUseRawMetricValuesInsteadOfScaledYValues() {
        val dashboard =
            analyzer.buildDashboard(
                history =
                    listOf(
                        DailyHealthSnapshot(
                            date = "2026-04-01",
                            weightKg = 80.0,
                            nutrition = NutritionSummary(caloriesKcal = 2100),
                        ),
                        DailyHealthSnapshot(
                            date = "2026-04-02",
                            weightKg = 81.0,
                            nutrition = NutritionSummary(caloriesKcal = 2500),
                        ),
                    ),
                selectedMetrics = setOf(ComparisonMetric.EATEN_CALORIES),
            )

        val chart = requireNotNull(dashboard.combinedChart)
        val eatenCalories = chart.lines.single { it.label == "Comidas" }

        assertEquals(listOf(2100.0, 2500.0), eatenCalories.rawValues)
        assertEquals(listOf("2100 kcal", "2500 kcal"), eatenCalories.formattedValues)
        assertEquals(
            listOf(
                ChartMarkerValue("Peso", "80 kg"),
                ChartMarkerValue("Comidas", "2100 kcal"),
            ),
            chart.markerValueMap[0f],
        )
        assertTrue(eatenCalories.yValues.none { it > 1000f })
    }
}
