package com.snabbt.fitnesstracker.fitness_tracker

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.patrykandpatrick.vico.views.cartesian.Scroll
import com.patrykandpatrick.vico.views.cartesian.ScrollHandler
import com.patrykandpatrick.vico.views.cartesian.Zoom
import com.patrykandpatrick.vico.views.cartesian.ZoomHandler
import com.patrykandpatrick.vico.views.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.views.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.views.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.views.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.views.cartesian.data.lineSeries
import com.patrykandpatrick.vico.views.cartesian.CartesianChartView
import com.patrykandpatrick.vico.views.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.views.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.views.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.views.common.Fill
import com.patrykandpatrick.vico.views.common.Insets
import com.patrykandpatrick.vico.views.common.data.ExtraStore
import com.patrykandpatrick.vico.views.common.component.LineComponent
import com.patrykandpatrick.vico.views.common.component.ShapeComponent
import com.patrykandpatrick.vico.views.common.component.TextComponent
import com.patrykandpatrick.vico.views.common.shape.CorneredShape
import com.patrykandpatrick.vico.views.common.shape.MarkerCorneredShape
import com.patrykandpatrick.vico.views.common.shape.Shape
import com.google.android.material.chip.Chip
import com.snabbt.fitnesstracker.fitness_tracker.databinding.ActivityHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var syncManager: HealthConnectSyncManager
    private val analyzer = HealthHistoryAnalyzer()
    private val combinedChartProducer = CartesianChartModelProducer()
    private val selectedMetrics =
        ComparisonMetric.values().toCollection(linkedSetOf())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        syncManager = HealthConnectSyncManager(applicationContext)
        binding.weightChartView.modelProducer = combinedChartProducer
        setupMetricControls()
        binding.comparisonChartsContainer.removeAllViews()
        binding.comparisonEmptyText.isVisible = false
        lifecycleScope.launch { renderDashboard() }
    }

    private fun setupMetricControls() {
        bindMetricChip(binding.bodyFatChip.id, ComparisonMetric.BODY_FAT)
        bindMetricChip(binding.bmrChip.id, ComparisonMetric.BMR)
        bindMetricChip(binding.activeCaloriesChip.id, ComparisonMetric.ACTIVE_CALORIES)
        bindMetricChip(binding.totalCaloriesChip.id, ComparisonMetric.TOTAL_CALORIES)
        bindMetricChip(binding.eatenCaloriesChip.id, ComparisonMetric.EATEN_CALORIES)
        bindMetricChip(binding.calorieBalanceChip.id, ComparisonMetric.CALORIE_BALANCE)
        bindMetricChip(binding.stepsChip.id, ComparisonMetric.STEPS)
        bindMetricChip(binding.sleepChip.id, ComparisonMetric.SLEEP)
        bindMetricChip(binding.exerciseChip.id, ComparisonMetric.EXERCISE)
        bindMetricChip(binding.proteinChip.id, ComparisonMetric.PROTEIN)
        bindMetricChip(binding.carbsChip.id, ComparisonMetric.CARBS)
        bindMetricChip(binding.fatChip.id, ComparisonMetric.FAT)
    }

    private fun bindMetricChip(
        chipId: Int,
        metric: ComparisonMetric,
    ) {
        val chip = binding.metricChipGroup.findViewById<com.google.android.material.chip.Chip>(chipId)
        chip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedMetrics += metric
            } else {
                selectedMetrics -= metric
            }
            lifecycleScope.launch { renderDashboard() }
        }
    }

    private suspend fun renderDashboard() {
        val metrics = selectedMetrics.toSet()
        runCatching {
            withContext(Dispatchers.IO) {
                analyzer.buildDashboard(syncManager.loadHistory(), metrics)
            }
        }.onSuccess { dashboard ->
            binding.dataSummaryText.text = dashboard.dataSummary
            binding.weightChartSummaryText.text = dashboard.combinedSummary
            binding.correlationSummaryText.text = dashboard.correlationSummary
            binding.comparisonChartsContainer.removeAllViews()
            binding.comparisonEmptyText.isVisible = false

            dashboard.combinedChart?.let { chart ->
                binding.weightChartView.isVisible = true
                binding.weightChartEmptyText.isVisible = false
                renderLegend(chart)
                renderChart(binding.weightChartView, combinedChartProducer, chart)
            } ?: run {
                binding.weightChartView.isVisible = false
                binding.weightChartEmptyText.isVisible = true
                binding.chartLegendChipGroup.removeAllViews()
                binding.chartLegendChipGroup.isVisible = false
            }
        }.onFailure { error ->
            binding.dataSummaryText.text = error.message ?: "No se pudo cargar el historial."
            binding.weightChartSummaryText.text = ""
            binding.correlationSummaryText.text = ""
            binding.comparisonChartsContainer.removeAllViews()
            binding.comparisonEmptyText.isVisible = true
            binding.weightChartView.isVisible = false
            binding.weightChartEmptyText.isVisible = true
            binding.chartLegendChipGroup.removeAllViews()
            binding.chartLegendChipGroup.isVisible = false
            showMessage(error.message ?: "No se pudo cargar el historial.")
        }
    }

    private fun renderLegend(chart: ChartSeriesSpec) {
        binding.chartLegendChipGroup.removeAllViews()
        chart.lines.forEachIndexed { index, line ->
            binding.chartLegendChipGroup.addView(
                Chip(this).apply {
                    text = line.label
                    isCheckable = false
                    isClickable = false
                    isChipIconVisible = true
                    chipIcon = legendIcon(chartColors[index % chartColors.size])
                    chipIconTint = null
                    chipIconSize = dp(10).toFloat()
                    layoutParams =
                        ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                },
            )
        }
        binding.chartLegendChipGroup.isVisible = chart.lines.isNotEmpty()
    }

    private suspend fun renderChart(
        chartView: CartesianChartView,
        modelProducer: CartesianChartModelProducer,
        chart: ChartSeriesSpec,
    ) {
        chartView.scrollHandler =
            ScrollHandler(
                scrollEnabled = true,
                initialScroll = Scroll.Absolute.End,
            )
        chartView.zoomHandler =
            ZoomHandler(
                zoomEnabled = true,
                initialZoom = Zoom.max(Zoom.x(chart.visibleXRange()), Zoom.Content),
                minZoom = Zoom.Content,
            )
        val lineLayer =
            LineCartesianLayer(
                lineProvider =
                    LineCartesianLayer.LineProvider.series(
                        chart.lines.mapIndexed { index, _ -> chartLine(index) },
                    ),
                rangeProvider =
                    CartesianLayerRangeProvider.fixed(
                        minX = chart.minX.toDouble(),
                        maxX = chart.maxX.toDouble(),
                        minY = chart.minY.toDouble(),
                        maxY = chart.maxY.toDouble(),
                    ),
            )
        chartView.chart =
            chartView.chart?.copy(
                lineLayer,
                bottomAxis =
                    HorizontalAxis.bottom(
                        valueFormatter =
                            CartesianValueFormatter { context, x, _ ->
                                val key = x.toFloat().roundToInt().toFloat()
                                context.model.extraStore[labelMapKey][key] ?: ""
                            },
                        itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { 2 }),
                    ),
                marker = createMarker(),
                markerController = CartesianMarkerController.toggleOnTap(),
            )

        modelProducer.runTransaction {
            lineSeries {
                chart.lines.forEach { line ->
                    series(line.xValues, line.yValues)
                }
            }
            extras { extraStore ->
                extraStore[labelMapKey] = chart.labelMap
                extraStore[markerValueMapKey] = chart.markerValueMap
            }
        }
    }

    private fun ChartSeriesSpec.visibleXRange(): Double =
        (visiblePointCount - 1).coerceAtLeast(1).toDouble()

    private fun chartLine(index: Int): LineCartesianLayer.Line {
        val color = chartColors[index % chartColors.size]
        return LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            stroke = LineCartesianLayer.LineStroke.Continuous(2.5f, Paint.Cap.ROUND),
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(
                        component = ShapeComponent(fill = Fill(color), shape = Shape.Rectangle),
                        sizeDp = 6f,
                    ),
                ),
        )
    }

    private fun createMarker(): DefaultCartesianMarker =
        DefaultCartesianMarker(
            label =
                TextComponent(
                    color = Color.WHITE,
                    textSizeSp = 12f,
                    lineCount = MARKER_LINE_COUNT,
                    padding = Insets(horizontalDp = 8f, verticalDp = 6f),
                    background =
                        ShapeComponent(
                            fill = Fill(Color.rgb(32, 38, 44)),
                            shape = MarkerCorneredShape(CorneredShape.rounded(8f)),
                            strokeFill = Fill(Color.argb(48, 255, 255, 255)),
                            strokeThicknessDp = 1f,
                        ),
                ),
            valueFormatter = markerValueFormatter(),
            labelPosition = DefaultCartesianMarker.LabelPosition.AroundPoint,
            indicator = { color ->
                ShapeComponent(
                    fill = Fill(Color.WHITE),
                    shape = CorneredShape.Pill,
                    strokeFill = Fill(color),
                    strokeThicknessDp = 2f,
                )
            },
            indicatorSizeDp = 10f,
            guideline =
                LineComponent(
                    fill = Fill(Color.argb(96, 32, 38, 44)),
                    thicknessDp = 1f,
                ),
        )

    private fun markerValueFormatter(): DefaultCartesianMarker.ValueFormatter =
        DefaultCartesianMarker.ValueFormatter { context, targets ->
            val x = targets.firstOrNull()?.x?.roundToInt()?.toFloat()
            if (x == null) {
                ""
            } else {
                val dateLabel = context.model.extraStore[labelMapKey][x].orEmpty()
                val values = context.model.extraStore[markerValueMapKey][x].orEmpty()
                buildString {
                    append(dateLabel)
                    values.chunked(MARKER_VALUES_PER_LINE).forEach { row ->
                        append('\n')
                        append(row.joinToString(separator = "  ") { "${it.label} ${it.value}" })
                    }
                }
            }
        }

    private fun legendIcon(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2).toFloat()
            setColor(color)
            setSize(dp(10), dp(10))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val labelMapKey = ExtraStore.Key<Map<Float, String>>()
        val markerValueMapKey = ExtraStore.Key<Map<Float, List<ChartMarkerValue>>>()
        const val MARKER_VALUES_PER_LINE = 2
        const val MARKER_LINE_COUNT = 8
        val chartColors =
            intArrayOf(
                Color.rgb(31, 119, 180),
                Color.rgb(255, 127, 14),
                Color.rgb(44, 160, 44),
                Color.rgb(214, 39, 40),
                Color.rgb(148, 103, 189),
                Color.rgb(140, 86, 75),
                Color.rgb(227, 119, 194),
                Color.rgb(127, 127, 127),
                Color.rgb(188, 189, 34),
                Color.rgb(23, 190, 207),
                Color.rgb(0, 73, 114),
                Color.rgb(181, 90, 0),
                Color.rgb(0, 115, 62),
            )
    }
}
