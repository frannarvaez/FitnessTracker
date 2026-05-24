package com.snabbt.fitnesstracker.fitness_tracker

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.snabbt.fitnesstracker.fitness_tracker.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var syncManager: HealthConnectSyncManager
    private var latestStatus: HealthConnectStatus? = null
    private var lastSyncResult: SyncResult? = null
    private var isBusy: Boolean = false
    private var hasStatusError: Boolean = false
    private val syncRangeOptions = SyncRangeOption.values().toList()
    private var selectedSyncRangeOption = SyncRangeOption.TWO_MONTHS

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { _ ->
            lifecycleScope.launch {
                refreshStatus()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        syncManager = HealthConnectSyncManager(applicationContext)

        setupSyncRangeSelector()

        binding.checkStatusButton.setOnClickListener {
            lifecycleScope.launch { refreshStatus() }
        }
        binding.connectButton.setOnClickListener {
            lifecycleScope.launch { requestHealthPermissions() }
        }
        binding.openHealthConnectButton.setOnClickListener {
            try {
                syncManager.openHealthConnect()
            } catch (error: Exception) {
                showMessage(error.message ?: "No se pudo abrir Health Connect.")
            }
        }
        binding.checkLatestButton.setOnClickListener {
            lifecycleScope.launch { checkLatestData() }
        }
        binding.shareButton.setOnClickListener {
            shareExports()
        }
        binding.viewHistoryButton.setOnClickListener {
            openHistory()
        }
        binding.syncButton.setOnClickListener {
            lifecycleScope.launch { syncNow() }
        }

        renderInitialState()
        lifecycleScope.launch { refreshStatus() }
    }

    private suspend fun requestHealthPermissions() {
        val permissions = withContext(Dispatchers.IO) { syncManager.permissionsToRequest() }
        if (permissions.isEmpty()) {
            showMessage("Health Connect no esta disponible todavia en este dispositivo.")
            return
        }
        requestPermissions.launch(permissions)
    }

    private suspend fun refreshStatus() {
        setBusy(true)
        runCatching {
            withContext(Dispatchers.IO) { syncManager.getStatus() }
        }.onSuccess { status ->
            latestStatus = status
            hasStatusError = false
            renderStatus(status)
        }.onFailure { error ->
            latestStatus = null
            hasStatusError = true
            renderStatusError(error.message ?: "No se pudo leer el estado.")
        }
        setBusy(false)
    }

    private suspend fun syncNow() {
        val requestedDays = requestedSyncDays()
        setBusy(true)
        runCatching {
            withContext(Dispatchers.IO) { syncManager.sync(requestedDays) }
        }.onSuccess { result ->
            lastSyncResult = result
            val latest = result.latestSnapshot
            val latestSummary =
                if (latest == null) {
                    "No hay snapshot disponible todavia."
                } else {
                    buildString {
                        appendLine("Ultimo snapshot: ${latest.date}")
                        appendLine("Peso: ${latest.weightKg ?: "-"} kg")
                        appendLine("Grasa corporal: ${latest.bodyFatPct ?: "-"} %")
                        appendLine("BMR: ${latest.bmrKcalPerDay ?: "-"} kcal/dia")
                        appendLine("Calorias activas: ${latest.activeCaloriesKcal ?: "-"} kcal")
                        appendLine("Calorias totales: ${latest.totalCaloriesKcal ?: "-"} kcal")
                        appendLine("Pasos: ${latest.steps ?: "-"}")
                        appendLine("Sueno: ${latest.sleepMinutes ?: "-"} min")
                        appendLine("Sesiones de ejercicio: ${latest.exercise.size}")
                        appendLine("Nutricion: ${latest.nutrition?.caloriesKcal ?: "-"} kcal")
                    }
                }

            renderResult(
                buildString {
                    appendLine("Sync completado.")
                    appendLine("Dias solicitados: ${result.requestedDays}")
                    appendLine("Dias leidos ahora: ${result.effectiveDays}")
                    appendLine("Dias guardados en memoria: ${result.exportedDays}")
                    appendLine("Directorio de export: ${result.exportDirectory}")
                    appendLine("Ruta snapshot: ${result.snapshotPath}")
                    appendLine("Ruta historico JSON: ${result.historyPath}")
                    if (result.notes.isNotEmpty()) {
                        appendLine()
                        appendLine("Notas:")
                        result.notes.forEach { appendLine("- $it") }
                    }
                    appendLine()
                    append(latestSummary.trim())
                }.trim()
            )

            refreshStatus()
        }.onFailure { error ->
            renderResult(error.message ?: "El sync ha fallado.")
        }
        setBusy(false)
    }

    private suspend fun checkLatestData() {
        setBusy(true)
        runCatching {
            withContext(Dispatchers.IO) { syncManager.inspectLatestData() }
        }.onSuccess { result ->
            renderResult(
                buildString {
                    appendLine("Comprobacion rapida completada.")
                    appendLine("Ventana accesible: ${result.accessWindowDescription}")
                    result.mostRecentPoint?.let { point ->
                        appendLine("Dato mas reciente: ${point.metric}")
                        appendLine("Momento: ${point.timestamp}")
                        appendLine("Detalle: ${point.details}")
                        appendLine("Fuente: ${point.sourcePackage}")
                    } ?: appendLine("No se ha encontrado ningun dato en la ventana accesible.")

                    if (result.points.isNotEmpty()) {
                        appendLine()
                        appendLine("Ultimo dato por tipo:")
                        result.points.forEach { point ->
                            appendLine("- ${point.metric}: ${point.timestamp} | ${point.details} | ${point.sourcePackage}")
                        }
                    }

                    if (result.notes.isNotEmpty()) {
                        appendLine()
                        appendLine("Notas:")
                        result.notes.forEach { appendLine("- $it") }
                    }
                }.trim()
            )
        }.onFailure { error ->
            renderResult(error.message ?: "No se pudo comprobar el ultimo dato.")
        }
        setBusy(false)
    }

    private fun renderStatus(status: HealthConnectStatus) {
        binding.statusHeadlineText.text = statusHeadline(status)
        binding.statusGuidanceText.text = statusGuidance(status)
        binding.statusText.text =
            buildString {
                appendLine("SDK status: ${status.sdkStatusLabel}")
                appendLine("Disponible: ${yesNo(status.isAvailable)}")
                appendLine("Permisos obligatorios: ${yesNo(status.hasCorePermissions)}")
                appendLine("Permiso historico: ${yesNo(status.hasHistoryPermission)}")
                appendLine("Historico soportado: ${yesNo(status.historyPermissionAvailable)}")
                appendLine("Directorio de export: ${status.exportDirectory}")
                if (status.missingCorePermissions.isNotEmpty()) {
                    appendLine()
                    appendLine("Permisos pendientes:")
                    status.missingCorePermissions.forEach { appendLine("- $it") }
                }
            }.trim()

        binding.openHealthConnectButton.text =
            if (status.isAvailable) {
                getString(R.string.open_health_connect_manage)
            } else {
                getString(R.string.open_health_connect_install)
            }
        binding.daysInputLayout.helperText = daysHelperText(status)
        applyStatusInteractivity(status)
    }

    private fun shareExports() {
        runCatching {
            syncManager.buildShareExportIntent()
        }.onSuccess { intent ->
            startActivity(Intent.createChooser(intent, "Compartir JSON"))
        }.onFailure { error ->
            showMessage(error.message ?: "No se pudo compartir la exportacion.")
        }
    }

    private fun openHistory() {
        if (!syncManager.hasHistoryExport()) {
            showMessage("Todavia no hay metricas exportadas. Ejecuta primero un sync.")
            return
        }
        startActivity(Intent(this, HistoryActivity::class.java))
    }

    private fun setBusy(isBusy: Boolean) {
        this.isBusy = isBusy
        binding.progressBar.isVisible = isBusy
        applyStatusInteractivity(latestStatus)
    }

    private fun yesNo(value: Boolean): String = if (value) "si" else "no"

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun applyStatusInteractivity(status: HealthConnectStatus?) {
        val canRequestHistoryPermission =
            status?.isAvailable == true &&
                status.historyPermissionAvailable &&
                !status.hasHistoryPermission
        val canRequestPermissions =
            status?.isAvailable == true &&
                (status.missingCorePermissions.isNotEmpty() || canRequestHistoryPermission)
        val canReadData = status?.isAvailable == true && status.hasCorePermissions
        val needsHealthConnectInstall = status != null && !status.isAvailable
        val hasExports = lastSyncResult != null || hasExistingExports()
        val hasHistory = syncManager.hasHistoryExport()
        val shouldShowAccessSection = needsHealthConnectInstall || canRequestPermissions

        binding.checkStatusButton.isVisible = hasStatusError
        binding.checkStatusButton.isEnabled = hasStatusError && !isBusy

        binding.accessSection.isVisible = shouldShowAccessSection
        binding.openHealthConnectButton.isEnabled = !isBusy

        binding.connectButton.isVisible = canRequestPermissions
        binding.connectButton.isEnabled = canRequestPermissions && !isBusy
        binding.connectButton.text =
            if (status?.missingCorePermissions?.isEmpty() == true && canRequestHistoryPermission) {
                getString(R.string.connect_history_permission)
            } else {
                getString(R.string.connect_permissions)
            }

        binding.daysInputLayout.isVisible = canReadData
        binding.daysInputLayout.isEnabled = canReadData && !isBusy
        binding.syncRangeAutoComplete.isEnabled = canReadData && !isBusy

        binding.checkLatestButton.isVisible = canReadData
        binding.checkLatestButton.isEnabled = canReadData && !isBusy

        binding.syncButton.isVisible = canReadData
        binding.syncButton.isEnabled = canReadData && !isBusy

        binding.viewHistoryButton.isVisible = hasHistory
        binding.viewHistoryButton.isEnabled = hasHistory && !isBusy

        binding.shareButton.isVisible = hasExports
        binding.shareButton.isEnabled = hasExports && !isBusy
        binding.analysisActionsRow.isVisible = hasHistory || hasExports

        binding.accessSectionText.text = accessSectionText(status)
        binding.syncSectionText.text = syncSectionText(status)
    }

    private fun hasExistingExports(): Boolean =
        runCatching { syncManager.buildShareExportIntent() }.isSuccess

    private fun renderInitialState() {
        binding.resultCard.isVisible = false
        hasStatusError = false
        binding.statusHeadlineText.text = getString(R.string.status_checking_headline)
        binding.statusGuidanceText.text = getString(R.string.status_checking_guidance)
        binding.statusText.text = ""
        binding.daysInputLayout.helperText = null
        applyStatusInteractivity(null)
    }

    private fun renderStatusError(message: String) {
        binding.statusHeadlineText.text = getString(R.string.status_error_headline)
        binding.statusGuidanceText.text = getString(R.string.status_error_guidance)
        binding.statusText.text = message
        binding.daysInputLayout.helperText = null
        applyStatusInteractivity(null)
    }

    private fun renderResult(text: String) {
        binding.resultText.text = text
        binding.resultCard.isVisible = text.isNotBlank()
    }

    private fun statusHeadline(status: HealthConnectStatus): String =
        when {
            !status.isAvailable -> "Instala o actualiza Health Connect"
            !status.hasCorePermissions -> "Faltan permisos de lectura"
            else -> "Todo listo para sincronizar"
        }

    private fun statusGuidance(status: HealthConnectStatus): String =
        when {
            !status.isAvailable ->
                "Abre la ficha de Health Connect para instalarlo o actualizarlo antes de sincronizar."
            !status.hasCorePermissions ->
                "Concede los permisos obligatorios de peso, sueno, pasos, nutricion y ejercicio para desbloquear la sincronizacion."
            status.missingCorePermissions.isNotEmpty() ->
                    "Puedes sincronizar ya. Concede los permisos pendientes para completar calorias de actividad y calorias totales."
            status.hasHistoryPermission ->
                "Tienes acceso historico completo. Puedes revisar el ultimo dato o exportar tantos dias como necesites."
            else ->
                "Ya puedes sincronizar. Si no concedes el permiso historico, el rango seguira limitado a 30 dias."
        }

    private fun accessSectionText(status: HealthConnectStatus?): String =
        when {
            status == null -> "Primero comprobare el estado de Health Connect para mostrarte solo las acciones utiles."
            !status.isAvailable -> "Esta app necesita que Health Connect este instalado y actualizado."
            !status.hasCorePermissions -> "Solo veras el boton de permisos mientras falten accesos por conceder."
            status.missingCorePermissions.isNotEmpty() -> "Los datos principales ya funcionan. Puedes conceder los permisos pendientes para completar las calorias de actividad."
            status.historyPermissionAvailable && !status.hasHistoryPermission -> "Los permisos principales ya estan concedidos. Puedes pedir acceso historico para sincronizar mas de 30 dias."
            else -> "Los permisos principales ya estan concedidos. Puedes abrir Health Connect para revisarlos cuando quieras."
        }

    private fun syncSectionText(status: HealthConnectStatus?): String =
        when {
            status == null -> "La sincronizacion se activara cuando termine la comprobacion del estado."
            !status.isAvailable -> "La sincronizacion estara disponible en cuanto Health Connect pueda abrirse en este dispositivo."
            !status.hasCorePermissions -> "Cuando concedas los permisos necesarios apareceran aqui los controles de sincronizacion."
            status.hasHistoryPermission -> "El selector empieza en 2 meses. Puedes subir hasta 5 anos o usar max para el rango completo recomendado."
            else -> "El selector empieza en 2 meses, pero sin permiso historico Health Connect solo leera 30 dias y conservara lo anterior guardado."
        }

    private fun daysHelperText(status: HealthConnectStatus): String =
        if (status.hasHistoryPermission) {
            "Seleccion inicial: 2 meses. Max equivale a ${syncManager.maxSyncDays(status)} dias."
        } else {
            "Max accesible ahora: ${syncManager.maxSyncDays(status)} dias. Los dias antiguos ya guardados se conservan."
        }

    private fun setupSyncRangeSelector() {
        val labels = syncRangeOptions.map { getString(it.labelRes) }
        binding.syncRangeAutoComplete.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels),
        )
        binding.syncRangeAutoComplete.setText(getString(selectedSyncRangeOption.labelRes), false)
        binding.syncRangeAutoComplete.setOnItemClickListener { _, _, position, _ ->
            selectedSyncRangeOption = syncRangeOptions[position]
        }
    }

    private fun requestedSyncDays(): Int =
        selectedSyncRangeOption.days ?: syncManager.maxSyncDays(latestStatus)

    private enum class SyncRangeOption(
        @param:StringRes val labelRes: Int,
        val days: Int?,
    ) {
        ONE_MONTH(R.string.sync_range_1_month, 30),
        TWO_MONTHS(R.string.sync_range_2_months, 60),
        THREE_MONTHS(R.string.sync_range_3_months, 90),
        SIX_MONTHS(R.string.sync_range_6_months, 180),
        ONE_YEAR(R.string.sync_range_1_year, 365),
        TWO_YEARS(R.string.sync_range_2_years, 730),
        FIVE_YEARS(R.string.sync_range_5_years, 1825),
        MAX(R.string.sync_range_max, null),
    }
}
