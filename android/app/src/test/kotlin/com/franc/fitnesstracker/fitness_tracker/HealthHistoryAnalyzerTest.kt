package com.snabbt.fitnesstracker.fitness_tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
                    "Quemadas",
                    "Total kcal",
                    "Comidas",
                    "Dif kcal",
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
