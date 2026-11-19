package chat.mural.core

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

data class LearningBackupData(
    val archive: Archive,
    val sessionReports: List<SessionReport> = emptyList(),
    val sentenceCards: List<SentenceCard> = emptyList(),
    val learnerMemories: Map<String, LearnerMemory> = emptyMap(),
)

object LearningBackupCodec {
    private const val MAX_REPORTS = 2_000
    private const val MAX_CARDS = 10_000
    private const val MAX_TICKETS = 10_000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val compactJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeBackup(data: LearningBackupData): String {
        val archiveObj = json.parseToJsonElement(ArchiveCodec.encode(data.archive)).jsonObject
        val cleanMemories = data.learnerMemories
            .filterKeys { LanguageRegistry.get(it) != null }
            .mapValues { (_, memory) -> memory.sanitized() }
            .filterValues { it.hasContent() || !it.autoUpdateEnabled || it.manualLevelOverride != null }
        val extended = JsonObject(
            archiveObj +
                ("sessionReports" to json.encodeToJsonElement(data.sessionReports.take(MAX_REPORTS))) +
                ("sentenceCards" to json.encodeToJsonElement(data.sentenceCards.take(MAX_CARDS))) +
                ("learnerMemories" to json.encodeToJsonElement(cleanMemories)),
        )
        val encoded = json.encodeToString(JsonObject.serializer(), extended)
        if (encoded.toByteArray(Charsets.UTF_8).size > ArchiveCodec.MAXIMUM_ENCODED_BYTES) {
            throw ArchiveError.TOO_LARGE
        }
        return encoded
    }

    fun decodeBackup(raw: String, importedAt: Double = nowSeconds()): LearningBackupData {
        val archive = ArchiveCodec.decode(raw).also { decoded ->
            decoded.sessions.filter { it.endedAt == null }.forEach {
                it.endedAt = importedAt
                it.endReason = "Imported unfinished conversation"
            }
        }
        val rootObj = json.parseToJsonElement(raw).jsonObject
        val (reports, cards, memories) = decodeExtendedFields(rootObj)
        return LearningBackupData(
            archive = archive,
            sessionReports = reports,
            sentenceCards = cards,
            learnerMemories = memories,
        )
    }

    fun mergeBackup(
        current: LearningBackupData,
        incoming: LearningBackupData,
    ): LearningBackupData {
        val mergedArchive = ArchiveCodec.merge(current.archive, incoming.archive)

        val existingReportIds = current.sessionReports.map { it.id.lowercase() }.toMutableSet()
        val existingReportSessionIds = current.sessionReports
            .mapNotNull { it.sessionID.trim().lowercase().takeIf(String::isNotEmpty) }
            .toMutableSet()
        val mergedReports = current.sessionReports.toMutableList()
        for (report in incoming.sessionReports) {
            if (LanguageRegistry.get(report.languageID) == null) continue
            val reportIdKey = report.id.lowercase()
            val sessionKey = report.sessionID.trim().lowercase()
            if (reportIdKey in existingReportIds) continue
            if (sessionKey.isNotEmpty() && sessionKey in existingReportSessionIds) continue
            mergedReports += report
            existingReportIds += reportIdKey
            if (sessionKey.isNotEmpty()) existingReportSessionIds += sessionKey
        }
        val finalReports = mergedReports.sortedByDescending { it.date }.take(MAX_REPORTS)

        val mergedCards = current.sentenceCards.map { card ->
            val allSessions = card.allSourceSessionIDs()
            if (allSessions != card.sourceSessionIDs) card.copy(sourceSessionIDs = allSessions) else card
        }.toMutableList()
        val existingCardIds = mergedCards.map { it.id.lowercase() }.toMutableSet()
        for (incomingCard in incoming.sentenceCards) {
            if (LanguageRegistry.get(incomingCard.languageID) == null) continue
            val cleanBack = incomingCard.backText.trim()
            if (cleanBack.isEmpty()) continue
            val incomingSessions = incomingCard.allSourceSessionIDs()
            val matchIndex = mergedCards.indexOfFirst {
                it.languageID == incomingCard.languageID &&
                    it.backText.trim().equals(cleanBack, ignoreCase = true)
            }
            if (matchIndex >= 0) {
                val existing = mergedCards[matchIndex]
                val combinedSessions = (existing.allSourceSessionIDs() + incomingSessions).distinct()
                mergedCards[matchIndex] = existing.copy(
                    sourceSessionID = existing.sourceSessionID.ifBlank { combinedSessions.lastOrNull().orEmpty() },
                    sourceSessionIDs = combinedSessions,
                )
            } else {
                val safeId = if (incomingCard.id.lowercase() in existingCardIds) {
                    UUID.randomUUID().toString()
                } else {
                    incomingCard.id
                }
                existingCardIds += safeId.lowercase()
                mergedCards += incomingCard.copy(
                    id = safeId,
                    sourceSessionID = incomingCard.sourceSessionID.ifBlank { incomingSessions.lastOrNull().orEmpty() },
                    sourceSessionIDs = incomingSessions,
                )
            }
        }
        val finalCards = mergedCards.take(MAX_CARDS)
        val finalMemories = LearnerMemoryReducer.mergeImported(current.learnerMemories, incoming.learnerMemories)

        val merged = LearningBackupData(
            archive = mergedArchive,
            sessionReports = finalReports,
            sentenceCards = finalCards,
            learnerMemories = finalMemories,
        )
        encodeBackup(merged)
        return merged
    }

    fun encodeStorageJson(
        data: LearningBackupData,
        finalAssessments: List<FinalAssessmentTicket>,
    ): String {
        val archiveObj = compactJson.parseToJsonElement(ArchiveCodec.encode(data.archive)).jsonObject
        val cleanMemories = data.learnerMemories
            .filterKeys { LanguageRegistry.get(it) != null }
            .mapValues { (_, memory) -> memory.sanitized() }
        val stored = JsonObject(
            archiveObj +
                ("androidFinalAssessments" to compactJson.encodeToJsonElement(finalAssessments.takeLast(MAX_TICKETS))) +
                ("sessionReports" to compactJson.encodeToJsonElement(data.sessionReports.take(MAX_REPORTS))) +
                ("sentenceCards" to compactJson.encodeToJsonElement(data.sentenceCards.take(MAX_CARDS))) +
                ("learnerMemories" to compactJson.encodeToJsonElement(cleanMemories)),
        )
        return stored.toString()
    }

    fun decodeStorageJson(raw: String): Pair<LearningBackupData, List<FinalAssessmentTicket>> {
        val archive = ArchiveCodec.decode(raw)
        val rootObj = compactJson.parseToJsonElement(raw).jsonObject
        val metadata = rootObj["androidFinalAssessments"]
        val tickets = metadata?.let { compactJson.decodeFromJsonElement<List<FinalAssessmentTicket>>(it) } ?: emptyList()
        val (reports, cards, memories) = decodeExtendedFields(rootObj)
        return LearningBackupData(
            archive = archive,
            sessionReports = reports,
            sentenceCards = cards,
            learnerMemories = memories,
        ) to tickets
    }

    private fun decodeExtendedFields(
        rootObj: JsonObject,
    ): Triple<List<SessionReport>, List<SentenceCard>, Map<String, LearnerMemory>> {
        val reports = rootObj["sessionReports"]?.let { element ->
            runCatching { compactJson.decodeFromJsonElement<List<SessionReport>>(element) }.getOrNull()
        }.orEmpty()
        val cards = rootObj["sentenceCards"]?.let { element ->
            runCatching { compactJson.decodeFromJsonElement<List<SentenceCard>>(element) }.getOrNull()
        }.orEmpty().map { card ->
            val allSessions = card.allSourceSessionIDs()
            if (allSessions != card.sourceSessionIDs) card.copy(sourceSessionIDs = allSessions) else card
        }
        val memories = rootObj["learnerMemories"]?.let { element ->
            runCatching { compactJson.decodeFromJsonElement<Map<String, LearnerMemory>>(element) }.getOrNull()
        }.orEmpty()
            .filterKeys { LanguageRegistry.get(it) != null }
            .mapValues { (_, memory) -> memory.sanitized() }
        return Triple(reports, cards, memories)
    }
}
