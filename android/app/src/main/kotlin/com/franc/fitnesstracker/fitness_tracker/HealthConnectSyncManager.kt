package com.snabbt.fitnesstracker.fitness_tracker

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.reflect.KClass

class HealthConnectSyncManager(
    private val context: Context,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val providerPackageName = "com.google.android.apps.healthdata"
    private val sharedExportRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/FitnessTracker/exports"
    private val sharedExportQueryPath = "$sharedExportRelativePath/"
    private val sharedExportDisplayPath = "Downloads/FitnessTracker/exports"
    private val zoneId: ZoneId
        get() = ZoneId.systemDefault()

    private val requiredReadPermissions =
        setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )
    private val activeCaloriesPermission = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    private val totalCaloriesPermission = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    private val optionalReadPermissions =
        setOf(
            activeCaloriesPermission,
            totalCaloriesPermission,
        )
    private val allReadPermissions = requiredReadPermissions + optionalReadPermissions

    private val historyPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    fun maxSyncDays(status: HealthConnectStatus?): Int =
        if (status?.hasHistoryPermission == true) {
            MAX_SYNC_DAYS_WITH_HISTORY
        } else {
            RECENT_ACCESS_DAYS
        }

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context, providerPackageName)

    suspend fun permissionsToRequest(): Set<String> {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) {
            return emptySet()
        }
        return if (isHistoryPermissionAvailable()) {
            allReadPermissions + historyPermission
        } else {
            allReadPermissions
        }
    }

    suspend fun getStatus(): HealthConnectStatus {
        val sdkStatus = sdkStatus()
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return HealthConnectStatus(
                sdkStatus = sdkStatus,
                sdkStatusLabel = sdkStatusLabel(sdkStatus),
                isAvailable = false,
                hasCorePermissions = false,
                hasHistoryPermission = false,
                historyPermissionAvailable = false,
                missingCorePermissions = allReadPermissions.toReadablePermissions(),
                exportDirectory = sharedExportDisplayPath,
            )
        }

        val client = client()
        val grantedPermissions = client.permissionController.getGrantedPermissions()
        val historyPermissionAvailable = isHistoryPermissionAvailable(client)
        val missingRequiredPermissions = requiredReadPermissions - grantedPermissions
        val missingReadPermissions = (allReadPermissions - grantedPermissions).toReadablePermissions()

        return HealthConnectStatus(
            sdkStatus = sdkStatus,
            sdkStatusLabel = sdkStatusLabel(sdkStatus),
            isAvailable = true,
            hasCorePermissions = missingRequiredPermissions.isEmpty(),
            hasHistoryPermission = grantedPermissions.contains(historyPermission),
            historyPermissionAvailable = historyPermissionAvailable,
            missingCorePermissions = missingReadPermissions,
            exportDirectory = sharedExportDisplayPath,
        )
    }

    fun openHealthConnect() {
        when (sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> {
                context.startActivity(
                    HealthConnectClient.getHealthConnectManageDataIntent(context, providerPackageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                val uriString =
                    "market://details?id=$providerPackageName&url=healthconnect%3A%2F%2Fonboarding"
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setPackage("com.android.vending")
                        data = uriString.toUri()
                        putExtra("overlay", true)
                        putExtra("callerId", context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }

            else -> {
                error("Health Connect no puede abrirse en este dispositivo.")
            }
        }
    }

    suspend fun inspectLatestData(): LatestDataResult {
        require(sdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
            "Health Connect no esta disponible en este dispositivo."
        }

        val client = client()
        val grantedPermissions = client.permissionController.getGrantedPermissions()
        require(grantedPermissions.containsAll(requiredReadPermissions)) {
            "Faltan permisos de lectura para Health Connect."
        }

        val hasHistoryPermission = grantedPermissions.contains(historyPermission)
        val startInstant =
            if (hasHistoryPermission) {
                Instant.EPOCH
            } else {
                Instant.now().minus(Duration.ofDays(RECENT_ACCESS_DAYS.toLong()))
            }
        val endInstant = Instant.now()

        val points =
            listOfNotNull(
                latestWeight(client, startInstant, endInstant),
                latestBodyFat(client, startInstant, endInstant),
                latestBasalMetabolicRate(client, startInstant, endInstant),
                if (grantedPermissions.contains(activeCaloriesPermission)) {
                    latestActiveCalories(client, startInstant, endInstant)
                } else {
                    null
                },
                if (grantedPermissions.contains(totalCaloriesPermission)) {
                    latestTotalCalories(client, startInstant, endInstant)
                } else {
                    null
                },
                latestNutrition(client, startInstant, endInstant),
                latestSleep(client, startInstant, endInstant),
                latestExercise(client, startInstant, endInstant),
                latestSteps(client, startInstant, endInstant),
            ).sortedByDescending { it.instant }

        val notes = mutableListOf<String>()
        if (!hasHistoryPermission) {
            notes +=
                "Sin permiso de historico, Health Connect solo deja leer hasta $RECENT_ACCESS_DAYS dias anteriores a la primera concesion de permisos."
        }
        if (!grantedPermissions.contains(activeCaloriesPermission)) {
            notes += "Falta READ_ACTIVE_CALORIES_BURNED; las calorias activas no se comprobaron."
        }
        if (!grantedPermissions.contains(totalCaloriesPermission)) {
            notes += "Falta READ_TOTAL_CALORIES_BURNED; no se pudo usar total-BMR como respaldo de calorias quemadas."
        }

        return LatestDataResult(
            accessWindowDescription =
                if (hasHistoryPermission) {
                    "historial accesible completo"
                } else {
                    "ultimos $RECENT_ACCESS_DAYS dias accesibles"
                },
            mostRecentPoint = points.firstOrNull()?.toPublicPoint(),
            points = points.map { it.toPublicPoint() },
            notes = notes,
        )
    }

    suspend fun sync(requestedDays: Int): SyncResult {
        require(sdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
            "Health Connect no esta disponible en este dispositivo."
        }

        val client = client()
        val grantedPermissions = client.permissionController.getGrantedPermissions()
        require(grantedPermissions.containsAll(requiredReadPermissions)) {
            "Faltan permisos de lectura para Health Connect."
        }

        val hasHistoryPermission = grantedPermissions.contains(historyPermission)
        val effectiveDays =
            if (hasHistoryPermission) {
                requestedDays.coerceAtLeast(1)
            } else {
                requestedDays.coerceIn(1, RECENT_ACCESS_DAYS)
            }
        val notes = mutableListOf<String>()
        if (!hasHistoryPermission && requestedDays > RECENT_ACCESS_DAYS) {
            notes += "Sin permiso de historico, Health Connect solo expone hasta $RECENT_ACCESS_DAYS dias anteriores."
        }
        if (hasHistoryPermission && requestedDays > MAX_SYNC_DAYS_WITH_HISTORY) {
            notes += "Solicitar muchos anos de historico puede tardar bastante; no hay un tope fijo en la app."
        }

        val today = LocalDate.now(zoneId)
        val startDate = today.minusDays((effectiveDays - 1).toLong())
        val endExclusiveDate = today.plusDays(1)
        val startInstant = startDate.atStartOfDay(zoneId).toInstant()
        val endInstant = endExclusiveDate.atStartOfDay(zoneId).toInstant()

        val builders = linkedMapOf<LocalDate, DailySnapshotBuilder>()
        var cursor = startDate
        while (!cursor.isAfter(today)) {
            builders[cursor] = DailySnapshotBuilder(cursor)
            cursor = cursor.plusDays(1)
        }

        aggregateSteps(client, startDate, endExclusiveDate, builders)
        val activeCaloriesAuthoritative =
            if (grantedPermissions.contains(activeCaloriesPermission)) {
                aggregateActiveCalories(client, startDate, endExclusiveDate, builders, notes)
            } else {
                notes += "Falta READ_ACTIVE_CALORIES_BURNED; se conserva cualquier valor previo de calorias activas."
                false
            }
        val totalCaloriesAuthoritative =
            if (grantedPermissions.contains(totalCaloriesPermission)) {
                aggregateTotalCalories(client, startDate, endExclusiveDate, builders, notes)
            } else {
                notes += "Falta READ_TOTAL_CALORIES_BURNED; se conserva cualquier valor previo de calorias totales."
                false
            }
        aggregateSleep(client, startDate, endExclusiveDate, builders)
        aggregateNutrition(client, startDate, endExclusiveDate, builders)
        readWeight(client, startInstant, endInstant, builders)
        readBodyFat(client, startInstant, endInstant, builders)
        readBasalMetabolicRate(client, startInstant, endInstant, builders)
        readExerciseSessions(client, startInstant, endInstant, builders)

        val freshHistory = builders.values.map { it.build() }
        val storedHistory = loadStoredHistoryForMerge(notes)
        val storedDates = storedHistory.mapTo(mutableSetOf()) { it.date }
        val freshDates = freshHistory.mapTo(mutableSetOf()) { it.date }
        val preservedStoredDays = (storedDates - freshDates).size
        val history =
            HealthHistoryMerger.mergeHistories(
                storedHistory,
                freshHistory,
                SnapshotMergePolicy(
                    activeCaloriesAuthoritative = activeCaloriesAuthoritative,
                    totalCaloriesAuthoritative = totalCaloriesAuthoritative,
                ),
            )
        val latestSnapshot = history.lastOrNull()

        if (preservedStoredDays > 0) {
            notes +=
                "Se conservaron $preservedStoredDays dias ya guardados fuera de la ventana leida ahora."
        }

        val snapshotJson = gson.toJson(latestSnapshot)
        val historyJson = gson.toJson(history)
        val exportArtifacts = writeExports(snapshotJson, historyJson, notes)

        return SyncResult(
            requestedDays = requestedDays,
            effectiveDays = effectiveDays,
            exportDirectory = exportArtifacts.exportDirectory,
            snapshotPath = exportArtifacts.snapshotPath,
            historyPath = exportArtifacts.historyPath,
            privateSnapshotPath = exportArtifacts.privateSnapshotPath,
            privateHistoryPath = exportArtifacts.privateHistoryPath,
            exportedDays = history.size,
            latestSnapshot = latestSnapshot,
            notes = exportArtifacts.notes,
        )
    }

    fun buildShareExportIntent(): Intent {
        val privateDir = privateExportDirectory()
        val files =
            listOf(
                File(privateDir, "health_snapshot.json"),
                File(privateDir, "health_history.json"),
            ).filter { it.exists() && it.isFile }

        require(files.isNotEmpty()) {
            "No hay exportaciones disponibles para compartir todavia."
        }

        val uris =
            files.map { file ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }

        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/json"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_SUBJECT, "FitnessTracker export")
            putExtra(
                Intent.EXTRA_TEXT,
                "Exportacion de FitnessTracker desde Health Connect.",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun hasHistoryExport(): Boolean = privateHistoryFile().exists()

    fun loadHistory(): List<DailyHealthSnapshot> {
        val historyFile = privateHistoryFile()
        require(historyFile.exists()) {
            "No hay historial exportado todavia. Ejecuta primero un sync."
        }
        return readHistoryFile(historyFile)
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context, providerPackageName)

    private suspend fun isHistoryPermissionAvailable(): Boolean = isHistoryPermissionAvailable(client())

    private suspend fun isHistoryPermissionAvailable(client: HealthConnectClient): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    private fun privateExportDirectory(): File =
        File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
            "exports",
        )

    private fun privateHistoryFile(): File = File(privateExportDirectory(), "health_history.json")

    private fun readHistoryFile(historyFile: File): List<DailyHealthSnapshot> {
        val historyType = object : TypeToken<List<DailyHealthSnapshot>>() {}.type
        return gson.fromJson<List<DailyHealthSnapshot>>(historyFile.readText(), historyType).orEmpty()
    }

    private fun loadStoredHistoryForMerge(notes: MutableList<String>): List<DailyHealthSnapshot> {
        val historyFile = privateHistoryFile()
        if (!historyFile.exists()) {
            return emptyList()
        }

        return runCatching {
            readHistoryFile(historyFile)
        }.onFailure { error ->
            notes +=
                "No se pudo reutilizar el historico privado existente (${error.message ?: "error desconocido"})."
        }.getOrDefault(emptyList())
    }

    private fun writeExports(
        snapshotJson: String,
        historyJson: String,
        baseNotes: List<String>,
    ): ExportArtifacts {
        val privateDir = privateExportDirectory().apply { mkdirs() }
        val privateSnapshotFile = File(privateDir, "health_snapshot.json").apply { writeText(snapshotJson) }
        val privateHistoryFile = File(privateDir, "health_history.json").apply { writeText(historyJson) }

        val notes = baseNotes.toMutableList()
        val sharedExportSucceeded =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    writeSharedExport("health_snapshot.json", snapshotJson)
                    writeSharedExport("health_history.json", historyJson)
                }.onFailure { error ->
                    notes +=
                        "No se pudo completar la copia compartida en Downloads (${error.message ?: "error desconocido"})."
                }.isSuccess
            } else {
                notes += "Android 9 no soporta la copia compartida en Downloads; se conserva la copia privada."
                false
            }

        notes += "Copia privada de respaldo: ${privateDir.absolutePath}"

        return if (sharedExportSucceeded) {
            notes += "Archivos compartidos en $sharedExportDisplayPath."
            ExportArtifacts(
                exportDirectory = sharedExportDisplayPath,
                snapshotPath = "$sharedExportDisplayPath/health_snapshot.json",
                historyPath = "$sharedExportDisplayPath/health_history.json",
                privateSnapshotPath = privateSnapshotFile.absolutePath,
                privateHistoryPath = privateHistoryFile.absolutePath,
                notes = notes,
            )
        } else {
            ExportArtifacts(
                exportDirectory = privateDir.absolutePath,
                snapshotPath = privateSnapshotFile.absolutePath,
                historyPath = privateHistoryFile.absolutePath,
                privateSnapshotPath = privateSnapshotFile.absolutePath,
                privateHistoryPath = privateHistoryFile.absolutePath,
                notes = notes,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeSharedExport(
        fileName: String,
        contents: String,
    ) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        deleteExistingSharedExport(fileName)

        val itemUri =
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, sharedExportRelativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: error("no se pudo reservar el fichero compartido")

        try {
            resolver.openOutputStream(itemUri, "w")?.bufferedWriter().use { writer ->
                requireNotNull(writer) { "no se pudo abrir la salida del fichero compartido" }
                writer.write(contents)
            }
            resolver.update(
                itemUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
        } catch (error: Exception) {
            resolver.delete(itemUri, null, null)
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteExistingSharedExport(fileName: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(fileName, sharedExportQueryPath),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val existingUri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                resolver.delete(existingUri, null, null)
            }
        }
    }

    private suspend fun aggregateSteps(
        client: HealthConnectClient,
        startDate: LocalDate,
        endExclusiveDate: LocalDate,
        builders: Map<LocalDate, DailySnapshotBuilder>,
    ) {
        val grouped =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter =
                        TimeRangeFilter.between(startDate.atStartOfDay(), endExclusiveDate.atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            )

        grouped.forEach { bucket ->
            val date = bucket.startTime.toLocalDate()
            builders[date]?.apply {
                steps = bucket.result[StepsRecord.COUNT_TOTAL]
                stepsSources.addAll(bucket.result.dataOrigins.packageNames())
            }
        }
    }

    private suspend fun aggregateActiveCalories(
        client: HealthConnectClient,
        startDate: LocalDate,
        endExclusiveDate: LocalDate,
        builders: Map<LocalDate, DailySnapshotBuilder>,
        notes: MutableList<String>,
    ): Boolean =
        runCatching {
            val grouped =
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                        timeRangeFilter =
                            TimeRangeFilter.between(startDate.atStartOfDay(), endExclusiveDate.atStartOfDay()),
                        timeRangeSlicer = Period.ofDays(1),
                    ),
                )

            grouped.forEach { bucket ->
                val date = bucket.startTime.toLocalDate()
                builders[date]?.apply {
                    activeCaloriesKcal =
                        bucket.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                            ?.inKilocalories
                            ?.roundToInt()
                    activeCaloriesSources.addAll(bucket.result.dataOrigins.packageNames())
                }
            }
        }.onFailure { error ->
            notes +=
                "No se pudieron leer calorias activas (${error.message ?: "error desconocido"}); se conserva cualquier valor previo."
        }.isSuccess

    private suspend fun aggregateTotalCalories(
        client: HealthConnectClient,
        startDate: LocalDate,
        endExclusiveDate: LocalDate,
        builders: Map<LocalDate, DailySnapshotBuilder>,
        notes: MutableList<String>,
    ): Boolean =
        runCatching {
            val grouped =
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                        timeRangeFilter =
                            TimeRangeFilter.between(startDate.atStartOfDay(), endExclusiveDate.atStartOfDay()),
                        timeRangeSlicer = Period.ofDays(1),
                    ),
                )

            grouped.forEach { bucket ->
                val date = bucket.startTime.toLocalDate()
                builders[date]?.apply {
                    totalCaloriesKcal =
                        bucket.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
                            ?.inKilocalories
                            ?.roundToInt()
                    totalCaloriesSources.addAll(bucket.result.dataOrigins.packageNames())
                }
            }
        }.onFailure { error ->
            notes +=
                "No se pudieron leer calorias totales (${error.message ?: "error desconocido"}); se conserva cualquier valor previo."
        }.isSuccess

    private suspend fun aggregateSleep(
        client: HealthConnectClient,
        startDate: LocalDate,
        endExclusiveDate: LocalDate,
        builders: Map<LocalDate, DailySnapshotBuilder>,
    ) {
        val grouped =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                    timeRangeFilter =
                        TimeRangeFilter.between(startDate.atStartOfDay(), endExclusiveDate.atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            )

        grouped.forEach { bucket ->
            val date = bucket.startTime.toLocalDate()
            builders[date]?.apply {
                sleepMinutes = bucket.result[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()
                sleepSources.addAll(bucket.result.dataOrigins.packageNames())
            }
        }
    }

    private suspend fun aggregateNutrition(
        client: HealthConnectClient,
        startDate: LocalDate,
        endExclusiveDate: LocalDate,
        builders: Map<LocalDate, DailySnapshotBuilder>,
    ) {
        val grouped =
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics =
                        setOf(
                            NutritionRecord.ENERGY_TOTAL,
                            NutritionRecord.PROTEIN_TOTAL,
                            NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL,
                            NutritionRecord.TOTAL_FAT_TOTAL,
                        ),
                    timeRangeFilter =
                        TimeRangeFilter.between(startDate.atStartOfDay(), endExclusiveDate.atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            )

        grouped.forEach { bucket ->
            val date = bucket.startTime.toLocalDate()
            builders[date]?.apply {
                nutrition =
                    NutritionSummary(
                        caloriesKcal = bucket.result[NutritionRecord.ENERGY_TOTAL]?.inKilocalories?.roundToInt(),
                        proteinG = bucket.result[NutritionRecord.PROTEIN_TOTAL]?.inGrams?.roundToInt(),
                        carbsG = bucket.result[NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL]?.inGrams?.roundToInt(),
                        fatG = bucket.result[NutritionRecord.TOTAL_FAT_TOTAL]?.inGrams?.roundToInt(),
                    ).takeIf { it.caloriesKcal != null || it.proteinG != null || it.carbsG != null || it.fatG != null }
                nutritionSources.addAll(bucket.result.dataOrigins.packageNames())
            }
        }
    }

    private suspend fun readWeight(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
        builders: Map<LocalDate, DailySnapshotBuilder>,
    ) {
        client.readAllRecords(WeightRecord::class, startInstant, endInstant).forEach { record ->
            builders[record.time.atZone(zoneId).toLocalDate()]?.apply {
                weightKg = record.weight.inKilograms.round(1)
                weightSources.clear()
                weightSources += record.metadata.dataOrigin.packageName
            }
        }
    }

    private suspend fun readBodyFat(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
        builders: Map<LocalDate, DailySnapshotBuilder>,
    ) {
        client.readAllRecords(BodyFatRecord::class, startInstant, endInstant).forEach { record ->
            builders[record.time.atZone(zoneId).toLocalDate()]?.apply {
                bodyFatPct = record.percentage.value.round(1)
                bodyFatSources.clear()
                bodyFatSources += record.metadata.dataOrigin.packageName
            }
        }
    }

    private suspend fun readBasalMetabolicRate(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
        builders: Map<LocalDate, DailySnapshotBuilder>,
    ) {
        client.readAllRecords(BasalMetabolicRateRecord::class, startInstant, endInstant).forEach { record ->
            builders[record.time.atZone(zoneId).toLocalDate()]?.apply {
                bmrKcalPerDay = (record.basalMetabolicRate.inWatts * KCAL_PER_DAY_PER_WATT).roundToInt()
                bmrSources.clear()
                bmrSources += record.metadata.dataOrigin.packageName
            }
        }
    }

    private suspend fun readExerciseSessions(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
        builders: Map<LocalDate, DailySnapshotBuilder>,
    ) {
        client.readAllRecords(ExerciseSessionRecord::class, startInstant, endInstant).forEach { record ->
            builders[record.startTime.atZone(zoneId).toLocalDate()]?.apply {
                exercise +=
                    ExerciseSessionSummary(
                        type = exerciseTypeLabel(record.exerciseType),
                        typeCode = record.exerciseType,
                        startTime = record.startTime.toString(),
                        endTime = record.endTime.toString(),
                        minutes = Duration.between(record.startTime, record.endTime).toMinutes(),
                        sourcePackage = record.metadata.dataOrigin.packageName,
                        title = record.title,
                        notes = record.notes,
                    )
                exerciseSources += record.metadata.dataOrigin.packageName
            }
        }
    }

    private suspend fun <T : Record> HealthConnectClient.readAllRecords(
        recordType: KClass<T>,
        startInstant: Instant,
        endInstant: Instant,
    ): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response =
                readRecords(
                    ReadRecordsRequest(
                        recordType = recordType,
                        timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                        ascendingOrder = true,
                        pageSize = READ_PAGE_SIZE,
                        pageToken = pageToken,
                    ),
                )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    private suspend fun latestWeight(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
    ): LatestMetricCandidate? =
        client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.let { record ->
            LatestMetricCandidate(
                metric = "Peso",
                instant = record.time,
                sourcePackage = record.metadata.dataOrigin.packageName.orUnknownSource(),
                details = "${record.weight.inKilograms.round(1)} kg",
            )
        }

    private suspend fun latestBodyFat(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
    ): LatestMetricCandidate? =
        client.readRecords(
            ReadRecordsRequest(
                recordType = BodyFatRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.let { record ->
            LatestMetricCandidate(
                metric = "Grasa corporal",
                instant = record.time,
                sourcePackage = record.metadata.dataOrigin.packageName.orUnknownSource(),
                details = "${record.percentage.value.round(1)} %",
            )
        }

    private suspend fun latestBasalMetabolicRate(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
    ): LatestMetricCandidate? =
        client.readRecords(
            ReadRecordsRequest(
                recordType = BasalMetabolicRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.let { record ->
            LatestMetricCandidate(
                metric = "BMR",
                instant = record.time,
                sourcePackage = record.metadata.dataOrigin.packageName.orUnknownSource(),
                details = "${(record.basalMetabolicRate.inWatts * KCAL_PER_DAY_PER_WATT).roundToInt()} kcal/dia",
            )
        }

    private suspend fun latestActiveCalories(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
    ): LatestMetricCandidate? =
        client.readRecords(
            ReadRecordsRequest(
                recordType = ActiveCaloriesBurnedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.let { record ->
            LatestMetricCandidate(
                metric = "Calorias activas",
                instant = record.endTime,
                sourcePackage = record.metadata.dataOrigin.packageName.orUnknownSource(),
                details = "${record.energy.inKilocalories.roundToInt()} kcal",
            )
        }

    private suspend fun latestTotalCalories(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
    ): LatestMetricCandidate? =
        client.readRecords(
            ReadRecordsRequest(
                recordType = TotalCaloriesBurnedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.let { record ->
            LatestMetricCandidate(
                metric = "Calorias totales",
                instant = record.endTime,
                sourcePackage = record.metadata.dataOrigin.packageName.orUnknownSource(),
                details = "${record.energy.inKilocalories.roundToInt()} kcal",
            )
        }

    private suspend fun latestNutrition(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
    ): LatestMetricCandidate? =
        client.readRecords(
            ReadRecordsRequest(
                recordType = NutritionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.let { record ->
            val details =
                listOfNotNull(
                    record.energy?.inKilocalories?.roundToInt()?.let { "$it kcal" },
                    record.protein?.inGrams?.roundToInt()?.let { "${it} g prot" },
                    record.totalCarbohydrate?.inGrams?.roundToInt()?.let { "${it} g carb" },
                    record.totalFat?.inGrams?.roundToInt()?.let { "${it} g grasa" },
                ).joinToString(" | ")
                    .ifBlank { "registro nutricional" }
            LatestMetricCandidate(
                metric = "Nutricion",
                instant = record.endTime,
                sourcePackage = record.metadata.dataOrigin.packageName.orUnknownSource(),
                details = details,
            )
        }

    private suspend fun latestSleep(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
    ): LatestMetricCandidate? =
        client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.let { record ->
            LatestMetricCandidate(
                metric = "Sueno",
                instant = record.endTime,
                sourcePackage = record.metadata.dataOrigin.packageName.orUnknownSource(),
                details = "${Duration.between(record.startTime, record.endTime).toMinutes()} min",
            )
        }

    private suspend fun latestExercise(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
    ): LatestMetricCandidate? =
        client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.let { record ->
            LatestMetricCandidate(
                metric = "Ejercicio",
                instant = record.endTime,
                sourcePackage = record.metadata.dataOrigin.packageName.orUnknownSource(),
                details =
                    "${exerciseTypeLabel(record.exerciseType)} | ${Duration.between(record.startTime, record.endTime).toMinutes()} min",
            )
        }

    private suspend fun latestSteps(
        client: HealthConnectClient,
        startInstant: Instant,
        endInstant: Instant,
    ): LatestMetricCandidate? =
        client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.let { record ->
            LatestMetricCandidate(
                metric = "Pasos",
                instant = record.endTime,
                sourcePackage = record.metadata.dataOrigin.packageName.orUnknownSource(),
                details = "${record.count} pasos",
            )
        }

    private fun sdkStatusLabel(status: Int): String =
        when (status) {
            HealthConnectClient.SDK_AVAILABLE -> "SDK_AVAILABLE"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED"
            else -> "SDK_UNAVAILABLE"
        }

    private fun exerciseTypeLabel(typeCode: Int): String =
        when (typeCode) {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "strength_training"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "biking"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swimming_pool"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "rowing"
            else -> "exercise_$typeCode"
        }

    private fun Set<String>.toReadablePermissions(): List<String> =
        map { permission ->
            when (permission) {
                historyPermission -> "READ_HEALTH_DATA_HISTORY"
                else -> permission.substringAfterLast('.')
            }
        }.sorted()

    private fun Collection<DataOrigin>.packageNames(): List<String> =
        map { it.packageName }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    private fun String?.orUnknownSource(): String = this?.takeIf { it.isNotBlank() } ?: "origen_desconocido"

    private fun Double.round(decimals: Int): Double {
        val multiplier = 10.0.pow(decimals)
        return (this * multiplier).roundToInt() / multiplier
    }

    private data class DailySnapshotBuilder(
        val date: LocalDate,
        var weightKg: Double? = null,
        var bodyFatPct: Double? = null,
        var bmrKcalPerDay: Int? = null,
        var activeCaloriesKcal: Int? = null,
        var totalCaloriesKcal: Int? = null,
        var steps: Long? = null,
        var sleepMinutes: Long? = null,
        var nutrition: NutritionSummary? = null,
        val exercise: MutableList<ExerciseSessionSummary> = mutableListOf(),
        val weightSources: LinkedHashSet<String> = linkedSetOf(),
        val bodyFatSources: LinkedHashSet<String> = linkedSetOf(),
        val bmrSources: LinkedHashSet<String> = linkedSetOf(),
        val activeCaloriesSources: LinkedHashSet<String> = linkedSetOf(),
        val totalCaloriesSources: LinkedHashSet<String> = linkedSetOf(),
        val stepsSources: LinkedHashSet<String> = linkedSetOf(),
        val sleepSources: LinkedHashSet<String> = linkedSetOf(),
        val nutritionSources: LinkedHashSet<String> = linkedSetOf(),
        val exerciseSources: LinkedHashSet<String> = linkedSetOf(),
    ) {
        fun build(): DailyHealthSnapshot =
            DailyHealthSnapshot(
                date = date.toString(),
                weightKg = weightKg,
                bodyFatPct = bodyFatPct,
                bmrKcalPerDay = bmrKcalPerDay,
                activeCaloriesKcal = activeCaloriesKcal,
                totalCaloriesKcal = totalCaloriesKcal,
                steps = steps,
                sleepMinutes = sleepMinutes,
                nutrition = nutrition,
                exercise = exercise.toList(),
                sources =
                    SnapshotSources(
                        weight = weightSources.toList(),
                        bodyFat = bodyFatSources.toList(),
                        bmr = bmrSources.toList(),
                        activeCalories = activeCaloriesSources.toList(),
                        totalCalories = totalCaloriesSources.toList(),
                        steps = stepsSources.toList(),
                        sleep = sleepSources.toList(),
                        nutrition = nutritionSources.toList(),
                        exercise = exerciseSources.toList(),
                    ),
            )
    }

    private data class LatestMetricCandidate(
        val metric: String,
        val instant: Instant,
        val sourcePackage: String,
        val details: String,
    ) {
        fun toPublicPoint(): LatestHealthDataPoint =
            LatestHealthDataPoint(
                metric = metric,
                timestamp = instant.atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' '),
                sourcePackage = sourcePackage,
                details = details,
            )
    }

    private data class ExportArtifacts(
        val exportDirectory: String,
        val snapshotPath: String,
        val historyPath: String,
        val privateSnapshotPath: String,
        val privateHistoryPath: String,
        val notes: List<String>,
    )

    private companion object {
        private const val KCAL_PER_DAY_PER_WATT = 20.6362855
        private const val RECENT_ACCESS_DAYS = 30
        private const val MAX_SYNC_DAYS_WITH_HISTORY = 3650
        private const val READ_PAGE_SIZE = 5000
    }
}
