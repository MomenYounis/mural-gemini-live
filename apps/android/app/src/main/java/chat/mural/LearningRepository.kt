package chat.mural

import android.content.Context
import android.util.AtomicFile
import chat.mural.core.Archive
import chat.mural.core.ArchiveCodec
import chat.mural.core.DailyActivityRecord
import chat.mural.core.FinalAssessmentRecovery
import chat.mural.core.FinalAssessmentTicket
import chat.mural.core.LearnerMemory
import chat.mural.core.LearningBackupCodec
import chat.mural.core.LearningBackupData
import chat.mural.core.LearningSettings
import chat.mural.core.SentenceCard
import chat.mural.core.SessionReport
import chat.mural.core.nowSeconds
import java.io.ByteArrayOutputStream
import java.io.File

/** App-private archive; API credentials are deliberately kept elsewhere. */
data class LearningSnapshot(
    val archive: Archive,
    val finalAssessments: List<FinalAssessmentTicket> = emptyList(),
    val sessionReports: List<SessionReport> = emptyList(),
    val sentenceCards: List<SentenceCard> = emptyList(),
    val learnerMemories: Map<String, LearnerMemory> = emptyMap(),
    val learningSettings: LearningSettings = LearningSettings(),
    val reviewActivityDays: List<DailyActivityRecord> = emptyList(),
)

class LearningRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "learning.json"))

    fun loadReadOnly(): LearningSnapshot {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return LearningSnapshot(Archive())
        return file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { "El respaldo local es demasiado grande." }
                output.write(buffer, 0, count)
            }
            val encoded = output.toString(Charsets.UTF_8.name())
            val (backupData, tickets) = LearningBackupCodec.decodeStorageJson(encoded)
            require(tickets.size <= 10_000 && tickets.map { Triple(it.sessionID, it.passageID, it.revisionKey) }.distinct().size == tickets.size)
            require(
                tickets.all {
                    it.attempts in 0..FinalAssessmentRecovery.MAX_ATTEMPTS &&
                        (it.lastAttemptAt == null || it.lastAttemptAt.isFinite())
                },
            )
            LearningSnapshot(
                archive = backupData.archive,
                finalAssessments = tickets,
                sessionReports = backupData.sessionReports,
                sentenceCards = backupData.sentenceCards,
                learnerMemories = backupData.learnerMemories,
                learningSettings = backupData.learningSettings,
                reviewActivityDays = backupData.reviewActivityDays,
            )
        }
    }

    fun load(): LearningSnapshot {
        val snapshot = loadReadOnly()
        val archive = snapshot.archive
        var tickets = snapshot.finalAssessments
        val unfinished = archive.sessions.filter { it.endedAt == null }
        val recoveredAt = nowSeconds()
        unfinished.forEach {
            it.endedAt = recoveredAt
            it.endReason = "App closed before finalization"
            tickets = FinalAssessmentRecovery.enqueue(it, tickets)
        }
        val recovered = snapshot.copy(
            archive = archive,
            finalAssessments = tickets,
        )
        if (unfinished.isNotEmpty()) save(recovered)
        return recovered
    }

    fun save(snapshot: LearningSnapshot) {
        // The archive, retry ledger, SRS cards, reports, and learner memory share one AtomicFile commit.
        val bytes = LearningBackupCodec.encodeStorageJson(
            data = LearningBackupData(
                archive = snapshot.archive,
                sessionReports = snapshot.sessionReports,
                sentenceCards = snapshot.sentenceCards,
                learnerMemories = snapshot.learnerMemories,
                learningSettings = snapshot.learningSettings,
                reviewActivityDays = snapshot.reviewActivityDays,
            ),
            finalAssessments = snapshot.finalAssessments,
        ).toByteArray(Charsets.UTF_8)
        require(bytes.size <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { "The learning archive reached its storage limit; export a backup." }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}
