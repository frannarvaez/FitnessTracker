package com.snabbt.fitnesstracker.fitness_tracker

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class ComparisonMetric(
    val displayName: String,
    val shortLabel: String,
) {
    ACTIVE_CALORIES("Calorias quemadas", "Quemadas"),
    EATEN_CALORIES("Calorias comidas", "Comidas"),
    CALORIE_BALANCE("Quemadas - comidas", "Dif kcal"),
    STEPS("Pasos", "Pasos"),
    PROTEIN("Proteina", "Proteina"),
    CARBS("Carbs", "Carbs"),
    FAT("Grasa", "Grasa"),
    ;

    fun valueOf(point: DailyHealthPoint): Double? =
        when (this) {
            ACTIVE_CALORIES -> point.burnedCaloriesKcal
            EATEN_CALORIES -> point.eatenCaloriesKcal
            CALORIE_BALANCE -> point.calorieBalanceKcal
            STEPS -> point.steps
            PROTEIN -> point.proteinG
            CARBS -> point.carbsG
            FAT -> point.fatG
        }
}

data class DailyHealthPoint(
    val date: LocalDate,
    val label: String,
    val weightKg: Double? = null,
    val activeCaloriesKcal: Double? = null,
    val totalCaloriesKcal: Double? = null,
    val bmrKcalPerDay: Double? = null,
    val eatenCaloriesKcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val steps: Double? = null,
) {
    val calorieBalanceKcal: Double?
        get() {
            val burned = burnedCaloriesKcal
            return if (burned != null && eatenCaloriesKcal != null) {
                burned - eatenCaloriesKcal
            } else {
                null
            }
        }

    val burnedCaloriesKcal: Double?
        get() =
            activeCaloriesKcal
                ?: if (totalCaloriesKcal != null && bmrKcalPerDay != null) {
                    (totalCaloriesKcal - bmrKcalPerDay).coerceAtLeast(0.0)
                } else {
                    null
                }
}

data class ChartLineSpec(
    val label: String,
    val xValues: List<Float>,
    val yValues: List<Float>,
)

data class ChartSeriesSpec(
    val labelMap: Map<Float, String>,
    val lines: List<ChartLineSpec>,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val visiblePointCount: Int,
)

data class HistoryDashboard(
    val dataSummary: String,
    val combinedSummary: String,
    val combinedChart: ChartSeriesSpec?,
    val correlationSummary: String,
)

class HealthHistoryAnalyzer {
    private val dayFormatter = DateTimeFormatter.ofPattern("dd MMM")

    fun buildDashboard(
        history: List<DailyHealthSnapshot>,
        selectedMetrics: Set<ComparisonMetric>,
    ): HistoryDashboard {
        require(history.isNotEmpty()) { "Todavia no hay historial exportado." }

        val points = dailyPoints(history)
        require(points.isNotEmpty()) { "No hay puntos diarios para mostrar." }

        val chart = buildCombinedChart(points, selectedMetrics)

        return HistoryDashboard(
            dataSummary = buildDataSummary(points),
            combinedSummary = buildCombinedSummary(points, chart),
            combinedChart = chart,
            correlationSummary = buildCorrelationSummary(points, selectedMetrics),
        )
    }

    private fun dailyPoints(history: List<DailyHealthSnapshot>): List<DailyHealthPoint> =
        history
            .sortedBy { it.date }
            .map { snapshot ->
                val date = LocalDate.parse(snapshot.date)
                DailyHealthPoint(
                    date = date,
                    label = date.format(dayFormatter),
                    weightKg = snapshot.weightKg,
                    activeCaloriesKcal = snapshot.activeCaloriesKcal?.toDouble(),
                    totalCaloriesKcal = snapshot.totalCaloriesKcal?.toDouble(),
                    bmrKcalPerDay = snapshot.bmrKcalPerDay?.toDouble(),
                    eatenCaloriesKcal = snapshot.nutrition?.caloriesKcal?.toDouble(),
                    proteinG = snapshot.nutrition?.proteinG?.toDouble(),
                    carbsG = snapshot.nutrition?.carbsG?.toDouble(),
                    fatG = snapshot.nutrition?.fatG?.toDouble(),
                    steps = snapshot.steps?.toDouble(),
                )
            }

    private fun buildCombinedChart(
        points: List<DailyHealthPoint>,
        selectedMetrics: Set<ComparisonMetric>,
    ): ChartSeriesSpec? {
        val yRange = weightAxisRange(points.mapNotNull { it.weightKg })
        val requestedLines =
            buildList {
                add(MetricLine("Peso", scaleToWeightAxis = false) { point -> point.weightKg })
                selectedMetrics.forEach { metric ->
                    add(MetricLine(metric.shortLabel) { point -> metric.valueOf(point) })
                }
            }.distinctBy { it.label }

        val chartLines =
            requestedLines.mapNotNull { line ->
                val entries =
                    points.mapIndexedNotNull { index, point ->
                        line.valueOf(point)?.let { value -> index.toFloat() to value }
                    }
                if (entries.isEmpty()) {
                    null
                } else {
                    ChartLineSpec(
                        label = line.label,
                        xValues = entries.map { it.first },
                        yValues =
                            if (line.scaleToWeightAxis) {
                                scaleToRange(entries.map { it.second }, yRange).map { it.toFloat() }
                            } else {
                                entries.map { it.second.toFloat() }
                            },
                    )
                }
            }

        if (chartLines.isEmpty()) return null

        return ChartSeriesSpec(
            labelMap = axisLabels(points),
            lines = chartLines,
            minX = 0f,
            maxX = (points.lastIndex).coerceAtLeast(0).toFloat(),
            minY = yRange.min.toFloat(),
            maxY = yRange.max.toFloat(),
            visiblePointCount = VISIBLE_DAYS,
        )
    }

    private fun buildDataSummary(points: List<DailyHealthPoint>): String {
        val first = points.first().date
        val last = points.last().date
        val visibleStart = last.minusDays((VISIBLE_DAYS - 1).toLong()).coerceAtLeast(first)
        return "${points.size} dias diarios | ${first.format(DateTimeFormatter.ISO_DATE)} a ${last.format(DateTimeFormatter.ISO_DATE)} | vista inicial: ${visibleStart.format(DateTimeFormatter.ISO_DATE)} a ${last.format(DateTimeFormatter.ISO_DATE)}"
    }

    private fun buildCombinedSummary(
        points: List<DailyHealthPoint>,
        chart: ChartSeriesSpec?,
    ): String {
        val lineText =
            chart
                ?.lines
                ?.joinToString { it.label }
                ?.ifBlank { "sin series" }
                ?: "sin series"
        val burnedCaloriesText =
            when {
                points.any { it.activeCaloriesKcal != null } -> ""
                points.any { it.burnedCaloriesKcal != null } ->
                    " | quemadas estimadas como calorias totales - BMR"
                else -> " | faltan calorias quemadas en el historico actual"
            }
        val missingBalance =
            if (points.none { it.calorieBalanceKcal != null }) {
                " | el balance necesita quemadas y comidas el mismo dia"
            } else {
                ""
            }
        val axisText =
            chart?.let {
                "Eje Y peso: ${formatAxisValue(it.minY.toDouble())}-${formatAxisValue(it.maxY.toDouble())} kg"
            } ?: "Eje Y peso: sin datos"
        return "$axisText. Peso en kg reales; otras series reescaladas visualmente al mismo rango: $lineText$burnedCaloriesText$missingBalance"
    }

    private fun axisLabels(points: List<DailyHealthPoint>): Map<Float, String> =
        points.mapIndexed { index, point -> index.toFloat() to point.label }.toMap()

    private fun buildCorrelationSummary(
        points: List<DailyHealthPoint>,
        selectedMetrics: Set<ComparisonMetric>,
    ): String {
        val lines = mutableListOf("Relaciones exploratorias con el peso:")
        val candidates =
            buildList {
                add(MetricLine(ComparisonMetric.CALORIE_BALANCE.displayName) { point -> point.calorieBalanceKcal })
                selectedMetrics.forEach { metric ->
                    add(MetricLine(metric.displayName) { point -> metric.valueOf(point) })
                }
            }.distinctBy { it.label }

        val correlations =
            candidates.mapNotNull { metric ->
                val aligned =
                    points.filter { point ->
                        point.weightKg != null && metric.valueOf(point) != null
                    }
                val weightValues = aligned.mapNotNull { it.weightKg }
                val metricValues = aligned.mapNotNull { metric.valueOf(it) }
                val correlation = pearson(weightValues, metricValues) ?: return@mapNotNull null
                Triple(metric.label, correlation, aligned.size)
            }.sortedByDescending { abs(it.second) }

        if (correlations.isEmpty()) {
            lines += "- No hay suficientes puntos compartidos entre peso y las metricas visibles."
        } else {
            correlations.forEach { (name, correlation, sampleCount) ->
                lines += "- $name: ${formatSigned(correlation)} (${strengthLabel(correlation)}, n=$sampleCount)"
            }
        }

        return lines.joinToString(separator = "\n")
    }

    private fun weightAxisRange(weights: List<Double>): AxisRange {
        if (weights.isEmpty()) {
            return AxisRange(DEFAULT_WEIGHT_MIN_KG, DEFAULT_WEIGHT_MAX_KG)
        }
        val minValue = weights.minOrNull() ?: DEFAULT_WEIGHT_MIN_KG
        val maxValue = weights.maxOrNull() ?: DEFAULT_WEIGHT_MAX_KG
        val min = floor((minValue - WEIGHT_PADDING_KG).coerceAtMost(DEFAULT_WEIGHT_MIN_KG))
        val max = ceil((maxValue + WEIGHT_PADDING_KG).coerceAtLeast(DEFAULT_WEIGHT_MAX_KG))
        return AxisRange(min, max.takeIf { it > min } ?: (min + DEFAULT_WEIGHT_SPAN_KG))
    }

    private fun scaleToRange(
        values: List<Double>,
        range: AxisRange,
    ): List<Double> {
        val minValue = values.minOrNull() ?: return emptyList()
        val maxValue = values.maxOrNull() ?: return emptyList()
        if (minValue == maxValue) {
            return values.map { (range.min + range.max) / 2.0 }
        }
        return values.map { value ->
            range.min + ((value - minValue) / (maxValue - minValue)) * (range.max - range.min)
        }
    }

    private fun formatAxisValue(value: Double): String {
        val rounded = (value * 10.0).roundToInt() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.roundToInt().toString() else rounded.toString()
    }

    private fun pearson(
        first: List<Double>,
        second: List<Double>,
    ): Double? {
        if (first.size < 3 || second.size < 3 || first.size != second.size) {
            return null
        }
        val firstMean = first.average()
        val secondMean = second.average()

        var numerator = 0.0
        var firstDenominator = 0.0
        var secondDenominator = 0.0

        first.indices.forEach { index ->
            val firstCentered = first[index] - firstMean
            val secondCentered = second[index] - secondMean
            numerator += firstCentered * secondCentered
            firstDenominator += firstCentered * firstCentered
            secondDenominator += secondCentered * secondCentered
        }

        if (firstDenominator == 0.0 || secondDenominator == 0.0) {
            return null
        }

        return numerator / kotlin.math.sqrt(firstDenominator * secondDenominator)
    }

    private fun strengthLabel(value: Double): String =
        when (abs(value)) {
            in 0.0..<0.2 -> "muy debil"
            in 0.2..<0.4 -> "debil"
            in 0.4..<0.6 -> "moderada"
            in 0.6..<0.8 -> "fuerte"
            else -> "muy fuerte"
        }

    private fun formatSigned(value: Double): String {
        val rounded = (value * 100.0).roundToInt() / 100.0
        return if (rounded >= 0) "+$rounded" else rounded.toString()
    }

    private fun LocalDate.coerceAtLeast(minimum: LocalDate): LocalDate =
        if (isBefore(minimum)) minimum else this

    private data class MetricLine(
        val label: String,
        val scaleToWeightAxis: Boolean = true,
        val valueOf: (DailyHealthPoint) -> Double?,
    )

    private data class AxisRange(
        val min: Double,
        val max: Double,
    )

    private companion object {
        const val VISIBLE_DAYS = 14
        const val DEFAULT_WEIGHT_MIN_KG = 50.0
        const val DEFAULT_WEIGHT_MAX_KG = 100.0
        const val DEFAULT_WEIGHT_SPAN_KG = DEFAULT_WEIGHT_MAX_KG - DEFAULT_WEIGHT_MIN_KG
        const val WEIGHT_PADDING_KG = 2.0
    }
}
