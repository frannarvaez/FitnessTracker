package com.snabbt.fitnesstracker.fitness_tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthHistoryMergerTest {
    @Test
    fun freshSnapshotReplacesStoredValuesInsideSyncWindow() {
        val stored =
            DailyHealthSnapshot(
                date = "2026-04-01",
                weightKg = 80.0,
                activeCaloriesKcal = 500,
                totalCaloriesKcal = 2500,
                steps = 10_000,
                nutrition = NutritionSummary(caloriesKcal = 2000),
                exercise =
                    listOf(
                        ExerciseSessionSummary(
                            type = "running",
                            typeCode = 1,
                            startTime = "2026-04-01T07:00:00Z",
                            endTime = "2026-04-01T07:30:00Z",
                            minutes = 30,
                            sourcePackage = "old.source",
                        ),
                    ),
                sources =
                    SnapshotSources(
                        weight = listOf("old.weight"),
                        activeCalories = listOf("old.active"),
                        totalCalories = listOf("old.total"),
                        steps = listOf("old.steps"),
                        nutrition = listOf("old.nutrition"),
                        exercise = listOf("old.exercise"),
                    ),
            )
        val fresh = DailyHealthSnapshot(date = "2026-04-01")

        val merged =
            HealthHistoryMerger.mergeHistories(
                storedHistory = listOf(stored),
                freshHistory = listOf(fresh),
                policy =
                    SnapshotMergePolicy(
                        activeCaloriesAuthoritative = true,
                        totalCaloriesAuthoritative = true,
                    ),
            )

        val snapshot = merged.single()
        assertNull(snapshot.weightKg)
        assertNull(snapshot.activeCaloriesKcal)
        assertNull(snapshot.totalCaloriesKcal)
        assertNull(snapshot.steps)
        assertNull(snapshot.nutrition)
        assertEquals(emptyList<ExerciseSessionSummary>(), snapshot.exercise)
        assertEquals(SnapshotSources(), snapshot.sources)
    }

    @Test
    fun optionalCaloriesArePreservedWhenReadsAreNotAuthoritative() {
        val stored =
            DailyHealthSnapshot(
                date = "2026-04-01",
                weightKg = 80.0,
                activeCaloriesKcal = 500,
                totalCaloriesKcal = 2500,
                sources =
                    SnapshotSources(
                        weight = listOf("old.weight"),
                        activeCalories = listOf("old.active"),
                        totalCalories = listOf("old.total"),
                    ),
            )
        val fresh =
            DailyHealthSnapshot(
                date = "2026-04-01",
                weightKg = 81.0,
                sources = SnapshotSources(weight = listOf("fresh.weight")),
            )

        val merged =
            HealthHistoryMerger.mergeHistories(
                storedHistory = listOf(stored),
                freshHistory = listOf(fresh),
                policy =
                    SnapshotMergePolicy(
                        activeCaloriesAuthoritative = false,
                        totalCaloriesAuthoritative = false,
                    ),
            )

        val snapshot = merged.single()
        assertEquals(81.0, requireNotNull(snapshot.weightKg), 0.0)
        assertEquals(500, snapshot.activeCaloriesKcal)
        assertEquals(2500, snapshot.totalCaloriesKcal)
        assertEquals(listOf("fresh.weight"), snapshot.sources.weight)
        assertEquals(listOf("old.active"), snapshot.sources.activeCalories)
        assertEquals(listOf("old.total"), snapshot.sources.totalCalories)
    }

    @Test
    fun storedDaysOutsideFreshWindowArePreserved() {
        val stored = DailyHealthSnapshot(date = "2026-03-31", weightKg = 80.0)
        val fresh = DailyHealthSnapshot(date = "2026-04-01", weightKg = 81.0)

        val merged =
            HealthHistoryMerger.mergeHistories(
                storedHistory = listOf(stored),
                freshHistory = listOf(fresh),
                policy =
                    SnapshotMergePolicy(
                        activeCaloriesAuthoritative = true,
                        totalCaloriesAuthoritative = true,
                    ),
            )

        assertEquals(listOf(stored, fresh), merged)
    }
}
