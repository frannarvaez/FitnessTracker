package com.snabbt.fitnesstracker.fitness_tracker

import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
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
import com.patrykandpatrick.vico.views.common.Fill
import com.patrykandpatrick.vico.views.common.data.ExtraStore
import com.patrykandpatrick.vico.views.common.component.ShapeComponent
import com.patrykandpatrick.vico.views.common.shape.Shape
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
        linkedSetOf(
            ComparisonMetric.ACTIVE_CALORIES,
            ComparisonMetric.EATEN_CALORIES,
            ComparisonMetric.CALORIE_BALANCE,
            ComparisonMetric.STEPS,
        )

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
        bindMetricChip(binding.activeCaloriesChip.id, ComparisonMetric.ACTIVE_CALORIES)
        bindMetricChip(binding.eatenCaloriesChip.id, ComparisonMetric.EATEN_CALORIES)
        bindMetricChip(binding.calorieBalanceChip.id, ComparisonMetric.CALORIE_BALANCE)
        bindMetricChip(binding.stepsChip.id, ComparisonMetric.STEPS)
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
                renderChart(binding.weightChartView, combinedChartProducer, chart)
            } ?: run {
                binding.weightChartView.isVisible = false
                binding.weightChartEmptyText.isVisible = true
            }
        }.onFailure { error ->
            binding.dataSummaryText.text = error.message ?: "No se pudo cargar el historial."
            binding.weightChartSummaryText.text = ""
            binding.correlationSummaryText.text = ""
            binding.comparisonChartsContainer.removeAllViews()
            binding.comparisonEmptyText.isVisible = true
            binding.weightChartView.isVisible = false
            binding.weightChartEmptyText.isVisible = true
            showMessage(error.message ?: "No se pudo cargar el historial.")
        }
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
            )

        modelProducer.runTransaction {
            lineSeries {
                chart.lines.forEach { line ->
                    series(line.xValues, line.yValues)
                }
            }
            extras { extraStore ->
                extraStore[labelMapKey] = chart.labelMap
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

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val labelMapKey = ExtraStore.Key<Map<Float, String>>()
        val chartColors =
            intArrayOf(
                Color.rgb(31, 119, 180),
                Color.rgb(214, 39, 40),
                Color.rgb(44, 160, 44),
                Color.rgb(148, 103, 189),
                Color.rgb(255, 127, 14),
                Color.rgb(23, 190, 207),
                Color.rgb(127, 127, 127),
                Color.rgb(188, 189, 34),
            )
    }
}
