package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Use Case / Interactor responsible for serializing a habit's full completion history
 * into a JSON string that can be written to a file via [Intent.ACTION_CREATE_DOCUMENT].
 *
 * Pure function — no side effects, no Room or ViewModel dependencies. Follows the
 * single-responsibility principle: only concerns itself with data → JSON mapping.
 *
 * Output format uses two internal serializable DTOs ([HabitHistoryExport] and
 * [CompletionRecord]) that decouple the export schema from the Room entity shape,
 * making the export format stable even if internal entities evolve.
 */
class ExportHistoryUseCase {

    /**
     * Root serializable DTO representing the full export payload for one habit.
     *
     * @property habitName Display name of the exported habit.
     * @property exportedAt ISO-8601 timestamp of when the export was generated.
     * @property completions Ordered list of all completion records (ascending by time).
     */
    @Serializable
    data class HabitHistoryExport(
        val habitName: String,
        val exportedAt: String,
        val completions: List<CompletionRecord>
    )

    /**
     * Serializable DTO for a single [HabitCompletionEntity] row.
     *
     * [LocalDateTime] is mapped to an ISO-8601 string here because
     * [kotlinx.serialization] has no built-in serializer for java.time types.
     */
    @Serializable
    data class CompletionRecord(
        val id: Int,
        val timestamp: String,
        val isTargetReached: Boolean
    )

    // ISO-8601 formatter used for all date-time strings in the export.
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    // Pretty-printed JSON for human-readable export files.
    private val json = Json { prettyPrint = true }

    /**
     * Builds a pretty-printed JSON string for the given [habitName] and its [completions].
     *
     * @param habitName The display name of the habit being exported.
     * @param completions All [HabitCompletionEntity] records belonging to the habit.
     * @return A UTF-8 JSON string suitable for writing to a `.json` file.
     */
    operator fun invoke(habitName: String, completions: List<HabitCompletionEntity>): String {
        val export = HabitHistoryExport(
            habitName = habitName,
            exportedAt = LocalDateTime.now().format(formatter),
            completions = completions
                .sortedBy { it.progressUpdate }
                .map { completion ->
                    CompletionRecord(
                        id = completion.id,
                        timestamp = completion.progressUpdate.format(formatter),
                        isTargetReached = completion.isTargetReached
                    )
                }
        )
        return json.encodeToString(export)
    }
}
