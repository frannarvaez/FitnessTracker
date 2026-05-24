package com.snabbt.fitnesstracker.fitness_tracker

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class ComparisonMetric(
    val displayName: String,
    val shortLabel: String,
) {
    BODY_FAT("Grasa corporal", "Grasa %"),
    BMR("BMR", "BMR"),
    ACTIVE_CALORIES("Calorias de actividad", "Actividad"),
    TOTAL_CALORIES("Calorias totales quemadas", "Total kcal"),
    EATEN_CALORIES("Calorias comidas", "Comidas"),
    CALORIE_BALANCE("Balance calorico", "Balance kcal"),
    STEPS("Pasos", "Pasos"),
    SLEEP("Sueno", "Sueno"),
    EXERCISE("Ejercicio", "Ejercicio"),
    PROTEIN("Proteina", "Proteina"),
    CARBS("Carbs", "Carbs"),
    FAT("Grasa", "Grasa"),
    ;

    fun valueOf(point: DailyHealthPoint): Double? =
        when (this) {
            BODY_FAT -> point.bodyFatPct
            BMR -> point.bmrKcalPerDay
            ACTIVE_CALORIES -> point.burnedCaloriesKcal
            TOTAL_CALORIES -> point.totalCaloriesKcal
            EATEN_CALORIES -> point.eatenCaloriesKcal
            CALORIE_BALANCE -> point.calorieBalanceKcal
            STEPS -> point.steps
            SLEEP -> point.sleepMinutes
            EXERCISE -> point.exerciseMinutes
            PROTEIN -> point.proteinG
            CARBS -> point.carbsG
            FAT -> point.fatG
        }

    companion object {
        fun userSelectableValues(): List<ComparisonMetric> =
            values().filterNot { it == TOTAL_CALORIES }
    }
}

data class DailyHealthPoint(
    val date: LocalDate,
    val label: String,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val activeCaloriesKcal: Double? = null,
    val totalCaloriesKcal: Double? = null,
    val bmrKcalPerDay: Double? = null,
    val eatenCaloriesKcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val steps: Double? = null,
    val sleepMinutes: Double? = null,
    val exerciseMinutes: Double? = null,
) {
    val calorieBalanceKcal: Double?
        get() {
            val totalBurned = totalBurnedCaloriesKcal
            return if (totalBurned != null && eatenCaloriesKcal != null) {
                totalBurned - eatenCaloriesKcal
            } else {
                null
            }
        }

    val totalBurnedCaloriesKcal: Double?
        get() =
            totalCaloriesKcal
                ?: run {
                    val activeBurned = burnedCaloriesKcal
                    if (bmrKcalPerDay != null && activeBurned != null) {
                        bmrKcalPerDay + activeBurned
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
    val rawValues: List<Double>,
    val formattedValues: List<String>,
)

data class ChartMarkerValue(
    val label: String,
    val value: String,
)

data class ChartSeriesSpec(
    val labelMap: Map<Float, String>,
    val markerValueMap: Map<Float, List<ChartMarkerValue>>,
    val lines: List<ChartLineSpec>,
    val missingLabels: List<String>,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val usesWeightAxis: Boolean,
    val visiblePointCount: Int,
)

data class HistoryDashboard(
    val dataSummary: String,
    val calorieBalanceSummaries: List<CalorieBalanceSummary>,
    val combinedSummary: String,
    val combinedChart: ChartSeriesSpec?,
    val correlationSummary: String,
)

data class CalorieBalanceSummary(
    val label: String,
    val valueText: String,
    val detailText: String,
    val netKcal: Double?,
    val burnedKcal: Double?,
    val eatenKcal: Double?,
    val completeDays: Int,
    val expectedDays: Int,
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
            calorieBalanceSummaries = buildCalorieBalanceSummaries(points),
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
                    bodyFatPct = snapshot.bodyFatPct,
                    activeCaloriesKcal = snapshot.activeCaloriesKcal?.toDouble(),
                    totalCaloriesKcal = snapshot.totalCaloriesKcal?.toDouble(),
                    bmrKcalPerDay = snapshot.bmrKcalPerDay?.toDouble(),
                    eatenCaloriesKcal = snapshot.nutrition?.caloriesKcal?.toDouble(),
                    proteinG = snapshot.nutrition?.proteinG?.toDouble(),
                    carbsG = snapshot.nutrition?.carbsG?.toDouble(),
                    fatG = snapshot.nutrition?.fatG?.toDouble(),
                    steps = snapshot.steps?.toDouble(),
                    sleepMinutes = snapshot.sleepMinutes?.toDouble(),
                    exerciseMinutes =
                        snapshot.exercise
                            .sumOf { it.minutes }
                            .takeIf { it > 0L }
                            ?.toDouble(),
                )
            }

    private fun buildCombinedChart(
        points: List<DailyHealthPoint>,
        selectedMetrics: Set<ComparisonMetric>,
    ): ChartSeriesSpec? {
        val yRange = weightAxisRange(points)
        val requestedLines =
            buildList {
                add(
                    MetricLine(
                        label = "Peso",
                        scaleToWeightAxis = false,
                        formatValue = ::formatWeightValue,
                    ) { point ->
                        point.weightKg
                    },
                )
                selectedMetrics.forEach { metric ->
                    add(
                        MetricLine(
                            label = metric.shortLabel,
                            formatValue = { value -> formatMetricValue(metric, value) },
                        ) { point ->
                            metric.valueOf(point)
                        },
                    )
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
                    val rawValues = entries.map { it.second }
                    ChartLineSpec(
                        label = line.label,
                        xValues = entries.map { it.first },
                        yValues =
                            if (line.scaleToWeightAxis) {
                                scaleToRange(rawValues, yRange).map { it.toFloat() }
                            } else {
                                rawValues.map { it.toFloat() }
                            },
                        rawValues = rawValues,
                        formattedValues = rawValues.map(line.formatValue),
                    )
                }
            }

        if (chartLines.isEmpty()) return null

        val visibleLineLabels = chartLines.mapTo(linkedSetOf()) { it.label }
        return ChartSeriesSpec(
            labelMap = axisLabels(points),
            markerValueMap = markerValueMap(chartLines),
            lines = chartLines,
            missingLabels = requestedLines.map { it.label }.filterNot { it in visibleLineLabels },
            minX = 0f,
            maxX = (points.lastIndex).coerceAtLeast(0).toFloat(),
            minY = yRange.min.toFloat(),
            maxY = yRange.max.toFloat(),
            usesWeightAxis = yRange.usesWeightAxis,
            visiblePointCount = VISIBLE_DAYS,
        )
    }

    private fun buildDataSummary(points: List<DailyHealthPoint>): String {
        val first = points.first().date
        val last = points.last().date
        val visibleStart = last.minusDays((VISIBLE_DAYS - 1).toLong()).coerceAtLeast(first)
        return "${points.size} dias diarios | ${first.format(DateTimeFormatter.ISO_DATE)} a ${last.format(DateTimeFormatter.ISO_DATE)} | vista inicial: ${visibleStart.format(DateTimeFormatter.ISO_DATE)} a ${last.format(DateTimeFormatter.ISO_DATE)}"
    }

    private fun buildCalorieBalanceSummaries(points: List<DailyHealthPoint>): List<CalorieBalanceSummary> =
        listOf(
            buildCalorieBalanceSummary(points, CALORIE_BALANCE_WEEK_DAYS, "Ultimos 7 dias"),
            buildCalorieBalanceSummary(points, CALORIE_BALANCE_MONTH_DAYS, "Ultimos 30 dias"),
        )

    private fun buildCalorieBalanceSummary(
        points: List<DailyHealthPoint>,
        dayCount: Int,
        label: String,
    ): CalorieBalanceSummary {
        val last = points.last().date
        val start = last.minusDays((dayCount - 1).toLong())
        val dateRange = "${start.format(DateTimeFormatter.ISO_DATE)} a ${last.format(DateTimeFormatter.ISO_DATE)}"
        val completeDays =
            points
                .filter { point -> !point.date.isBefore(start) && !point.date.isAfter(last) }
                .mapNotNull { point ->
                    val burned = point.totalBurnedCaloriesKcal ?: return@mapNotNull null
                    val eaten = point.eatenCaloriesKcal ?: return@mapNotNull null
                    CompleteCalorieBalanceDay(burnedKcal = burned, eatenKcal = eaten)
                }

        if (completeDays.isEmpty()) {
            return CalorieBalanceSummary(
                label = label,
                valueText = "Sin datos completos",
                detailText = "0/$dayCount dias completos | $dateRange",
                netKcal = null,
                burnedKcal = null,
                eatenKcal = null,
                completeDays = 0,
                expectedDays = dayCount,
            )
        }

        val burned = completeDays.sumOf { it.burnedKcal }
        val eaten = completeDays.sumOf { it.eatenKcal }
        val net = burned - eaten
        return CalorieBalanceSummary(
            label = label,
            valueText = "${formatSignedWhole(net)} kcal",
            detailText =
                "Quemadas ${formatWhole(burned)} kcal | Consumidas ${formatWhole(eaten)} kcal\n" +
                    "${completeDays.size}/$dayCount dias completos | $dateRange",
            netKcal = net,
            burnedKcal = burned,
            eatenKcal = eaten,
            completeDays = completeDays.size,
            expectedDays = dayCount,
        )
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
                    " | actividad estimada como calorias totales - BMR"
                else -> " | faltan calorias de actividad en el historico actual"
            }
        val missingBalance =
            if (points.none { it.calorieBalanceKcal != null }) {
                " | el balance necesita comidas y calorias totales, o BMR + actividad, el mismo dia"
            } else {
                ""
            }
        val axisText =
            chart?.let {
                if (it.usesWeightAxis) {
                    "Eje Y peso: ${formatAxisValue(it.minY.toDouble())}-${formatAxisValue(it.maxY.toDouble())} kg"
                } else {
                    "Eje Y normalizado: ${formatAxisValue(it.minY.toDouble())}-${formatAxisValue(it.maxY.toDouble())}"
                }
            } ?: "Eje Y peso: sin datos"
        val scaleText =
            if (chart?.usesWeightAxis == true) {
                "Peso en kg reales; otras series reescaladas visualmente al mismo rango"
            } else {
                "Todas las series visibles estan reescaladas para comparar forma y fechas"
            }
        val missingText =
            chart
                ?.missingLabels
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = " | sin datos en el historial: ")
                .orEmpty()
        return "$axisText. $scaleText: $lineText$burnedCaloriesText$missingBalance$missingText"
    }

    private fun axisLabels(points: List<DailyHealthPoint>): Map<Float, String> =
        points.mapIndexed { index, point -> index.toFloat() to point.label }.toMap()

    private fun markerValueMap(lines: List<ChartLineSpec>): Map<Float, List<ChartMarkerValue>> {
        val valuesByX = linkedMapOf<Float, MutableList<ChartMarkerValue>>()
        lines.forEach { line ->
            line.xValues.forEachIndexed { index, x ->
                valuesByX.getOrPut(x) { mutableListOf() } +=
                    ChartMarkerValue(
                        label = line.label,
                        value = line.formattedValues[index],
                    )
            }
        }
        return valuesByX
    }

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

    private fun weightAxisRange(points: List<DailyHealthPoint>): AxisRange {
        val weights = points.mapNotNull { it.weightKg }
        if (weights.isEmpty()) {
            return AxisRange(NORMALIZED_AXIS_MIN, NORMALIZED_AXIS_MAX, usesWeightAxis = false)
        }
        val minValue = requireNotNull(weights.minOrNull())
        val maxValue = requireNotNull(weights.maxOrNull())
        val observedSpan = maxValue - minValue
        val historyDays =
            points
                .takeIf { it.isNotEmpty() }
                ?.let { ChronoUnit.DAYS.between(it.first().date, it.last().date) + 1L }
                ?: 1L
        val padding =
            (observedSpan * WEIGHT_PADDING_RATIO)
                .coerceIn(MIN_WEIGHT_PADDING_KG, MAX_WEIGHT_PADDING_KG)
        val minimumSpan = minimumWeightAxisSpan(historyDays)
        var min = minValue - padding
        var max = maxValue + padding

        if (max - min < minimumSpan) {
            val center = (minValue + maxValue) / 2.0
            min = center - minimumSpan / 2.0
            max = center + minimumSpan / 2.0
        }

        val roundedMin = floor(min * 10.0) / 10.0
        val roundedMax = ceil(max * 10.0) / 10.0
        return AxisRange(
            min = roundedMin,
            max = roundedMax.takeIf { it > roundedMin } ?: (roundedMin + minimumSpan),
            usesWeightAxis = true,
        )
    }

    private fun minimumWeightAxisSpan(historyDays: Long): Double =
        when {
            historyDays >= LONG_HISTORY_DAYS -> LONG_HISTORY_WEIGHT_SPAN_KG
            historyDays >= MEDIUM_HISTORY_DAYS -> MEDIUM_HISTORY_WEIGHT_SPAN_KG
            else -> RECENT_WEIGHT_SPAN_KG
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

    private fun formatWeightValue(value: Double): String = "${formatOneDecimal(value)} kg"

    private fun formatMetricValue(
        metric: ComparisonMetric,
        value: Double,
    ): String =
        when (metric) {
            ComparisonMetric.BODY_FAT -> "${formatOneDecimal(value)}%"
            ComparisonMetric.BMR,
            ComparisonMetric.ACTIVE_CALORIES,
            ComparisonMetric.TOTAL_CALORIES,
            ComparisonMetric.EATEN_CALORIES -> "${formatWhole(value)} kcal"
            ComparisonMetric.CALORIE_BALANCE -> "${formatSignedWhole(value)} kcal"
            ComparisonMetric.STEPS -> "${formatWhole(value)} pasos"
            ComparisonMetric.SLEEP,
            ComparisonMetric.EXERCISE -> formatMinutes(value)
            ComparisonMetric.PROTEIN,
            ComparisonMetric.CARBS,
            ComparisonMetric.FAT -> "${formatWhole(value)} g"
        }

    private fun formatWhole(value: Double): String = value.roundToInt().toString()

    private fun formatSignedWhole(value: Double): String {
        val rounded = value.roundToInt()
        return if (rounded > 0) "+$rounded" else rounded.toString()
    }

    private fun formatOneDecimal(value: Double): String {
        val rounded = (value * 10.0).roundToInt() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.roundToInt().toString() else rounded.toString()
    }

    private fun formatMinutes(value: Double): String {
        val minutes = value.roundToInt()
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return if (hours > 0) {
            "${hours} h ${remainingMinutes} min"
        } else {
            "$minutes min"
        }
    }

    private fun LocalDate.coerceAtLeast(minimum: LocalDate): LocalDate =
        if (isBefore(minimum)) minimum else this

    private data class MetricLine(
        val label: String,
        val scaleToWeightAxis: Boolean = true,
        val formatValue: (Double) -> String = { it.toString() },
        val valueOf: (DailyHealthPoint) -> Double?,
    )

    private data class AxisRange(
        val min: Double,
        val max: Double,
        val usesWeightAxis: Boolean,
    )

    private data class CompleteCalorieBalanceDay(
        val burnedKcal: Double,
        val eatenKcal: Double,
    )

    private companion object {
        const val VISIBLE_DAYS = 14
        const val CALORIE_BALANCE_WEEK_DAYS = 7
        const val CALORIE_BALANCE_MONTH_DAYS = 30
        const val NORMALIZED_AXIS_MIN = 0.0
        const val NORMALIZED_AXIS_MAX = 100.0
        const val WEIGHT_PADDING_RATIO = 0.12
        const val MIN_WEIGHT_PADDING_KG = 0.3
        const val MAX_WEIGHT_PADDING_KG = 1.5
        const val MEDIUM_HISTORY_DAYS = 180L
        const val LONG_HISTORY_DAYS = 730L
        const val RECENT_WEIGHT_SPAN_KG = 1.5
        const val MEDIUM_HISTORY_WEIGHT_SPAN_KG = 3.0
        const val LONG_HISTORY_WEIGHT_SPAN_KG = 6.0
    }
}
