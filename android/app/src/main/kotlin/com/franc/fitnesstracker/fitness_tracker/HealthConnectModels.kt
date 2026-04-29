package com.snabbt.fitnesstracker.fitness_tracker

data class HealthConnectStatus(
    val sdkStatus: Int,
    val sdkStatusLabel: String,
    val isAvailable: Boolean,
    val hasCorePermissions: Boolean,
    val hasHistoryPermission: Boolean,
    val historyPermissionAvailable: Boolean,
    val missingCorePermissions: List<String>,
    val exportDirectory: String,
)

data class NutritionSummary(
    val caloriesKcal: Int? = null,
    val proteinG: Int? = null,
    val carbsG: Int? = null,
    val fatG: Int? = null,
)

data class ExerciseSessionSummary(
    val type: String,
    val typeCode: Int,
    val startTime: String,
    val endTime: String,
    val minutes: Long,
    val sourcePackage: String,
    val title: String? = null,
    val notes: String? = null,
)

data class SnapshotSources(
    val weight: List<String> = emptyList(),
    val bodyFat: List<String> = emptyList(),
    val bmr: List<String> = emptyList(),
    val activeCalories: List<String> = emptyList(),
    val totalCalories: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val sleep: List<String> = emptyList(),
    val nutrition: List<String> = emptyList(),
    val exercise: List<String> = emptyList(),
)

data class DailyHealthSnapshot(
    val date: String,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val bmrKcalPerDay: Int? = null,
    val activeCaloriesKcal: Int? = null,
    val totalCaloriesKcal: Int? = null,
    val steps: Long? = null,
    val sleepMinutes: Long? = null,
    val nutrition: NutritionSummary? = null,
    val exercise: List<ExerciseSessionSummary> = emptyList(),
    val sources: SnapshotSources = SnapshotSources(),
)

data class SyncResult(
    val requestedDays: Int,
    val effectiveDays: Int,
    val exportDirectory: String,
    val snapshotPath: String,
    val historyPath: String,
    val privateSnapshotPath: String,
    val privateHistoryPath: String,
    val exportedDays: Int,
    val latestSnapshot: DailyHealthSnapshot?,
    val notes: List<String> = emptyList(),
)

data class LatestHealthDataPoint(
    val metric: String,
    val timestamp: String,
    val sourcePackage: String,
    val details: String,
)

data class LatestDataResult(
    val accessWindowDescription: String,
    val mostRecentPoint: LatestHealthDataPoint?,
    val points: List<LatestHealthDataPoint>,
    val notes: List<String> = emptyList(),
)
