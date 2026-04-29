package com.snabbt.fitnesstracker.fitness_tracker

internal data class SnapshotMergePolicy(
    val activeCaloriesAuthoritative: Boolean,
    val totalCaloriesAuthoritative: Boolean,
)

internal object HealthHistoryMerger {
    fun mergeHistories(
        storedHistory: List<DailyHealthSnapshot>,
        freshHistory: List<DailyHealthSnapshot>,
        policy: SnapshotMergePolicy,
    ): List<DailyHealthSnapshot> {
        val storedByDate = storedHistory.associateBy { it.date }
        val freshByDate = freshHistory.associateBy { it.date }
        return (storedByDate.keys + freshByDate.keys)
            .sorted()
            .map { date ->
                val stored = storedByDate[date]
                val fresh = freshByDate[date]
                when {
                    stored == null -> requireNotNull(fresh)
                    fresh == null -> stored
                    else -> mergeSnapshot(stored, fresh, policy)
                }
            }
    }

    private fun mergeSnapshot(
        stored: DailyHealthSnapshot,
        fresh: DailyHealthSnapshot,
        policy: SnapshotMergePolicy,
    ): DailyHealthSnapshot =
        DailyHealthSnapshot(
            date = fresh.date,
            weightKg = fresh.weightKg,
            bodyFatPct = fresh.bodyFatPct,
            bmrKcalPerDay = fresh.bmrKcalPerDay,
            activeCaloriesKcal =
                if (policy.activeCaloriesAuthoritative) {
                    fresh.activeCaloriesKcal
                } else {
                    stored.activeCaloriesKcal
                },
            totalCaloriesKcal =
                if (policy.totalCaloriesAuthoritative) {
                    fresh.totalCaloriesKcal
                } else {
                    stored.totalCaloriesKcal
                },
            steps = fresh.steps,
            sleepMinutes = fresh.sleepMinutes,
            nutrition = fresh.nutrition,
            exercise = fresh.exercise,
            sources = mergeSources(stored.sources, fresh.sources, policy),
        )

    private fun mergeSources(
        stored: SnapshotSources?,
        fresh: SnapshotSources?,
        policy: SnapshotMergePolicy,
    ): SnapshotSources =
        SnapshotSources(
            weight = fresh?.weight.cleanSources(),
            bodyFat = fresh?.bodyFat.cleanSources(),
            bmr = fresh?.bmr.cleanSources(),
            activeCalories =
                if (policy.activeCaloriesAuthoritative) {
                    fresh?.activeCalories.cleanSources()
                } else {
                    stored?.activeCalories.cleanSources()
                },
            totalCalories =
                if (policy.totalCaloriesAuthoritative) {
                    fresh?.totalCalories.cleanSources()
                } else {
                    stored?.totalCalories.cleanSources()
                },
            steps = fresh?.steps.cleanSources(),
            sleep = fresh?.sleep.cleanSources(),
            nutrition = fresh?.nutrition.cleanSources(),
            exercise = fresh?.exercise.cleanSources(),
        )

    private fun List<String>?.cleanSources(): List<String> =
        orEmpty()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
}
