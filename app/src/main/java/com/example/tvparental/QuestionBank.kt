package com.example.tvparental

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TvQuestion(
    val id: String,
    val prompt: String,
    val options: List<String>,
    val answer: String,
    val category: String,
    val difficulty: String,
)

data class ChallengeProgress(
    val answeredCount: Int,
    val correctCount: Int,
) {
    val complete: Boolean get() = answeredCount == QuestionBank.CHALLENGE_QUESTION_COUNT
    val passed: Boolean get() = complete && correctCount >= QuestionBank.CHALLENGE_REQUIRED_CORRECT
}

data class PendingAnswer(
    val attemptId: String,
    val challengeId: String,
    val question: TvQuestion,
    val selectedAnswer: String,
    val answeredAt: Long,
    val date: String,
    val answered: Boolean = true,
)

data class ChallengeQuestionSnapshot(
    val questionId: String,
    val prompt: String,
)

data class PendingChallengeRecord(
    val sessionId: String,
    val date: String,
    val startedAt: Long,
    val endedAt: Long,
    val status: String,
    val answeredCount: Int,
    val correctCount: Int,
    val questions: List<ChallengeQuestionSnapshot>,
    val interruptedQuestionId: String,
)

object QuestionBank {
    const val CHALLENGE_QUESTION_COUNT = 5
    const val CHALLENGE_REQUIRED_CORRECT = 4
    private const val PREFERENCES = "question_state"
    private const val LAST_QUESTION_ID = "last_question_id"
    private const val PENDING_CORRECT_ANSWERS = "pending_correct_answers"
    private const val CHALLENGE_ACTIVE = "challenge_active"
    private const val CHALLENGE_ANSWER_COUNT = "challenge_answer_count"
    private const val CHALLENGE_CORRECT_COUNT = "challenge_correct_count"
    private const val CHALLENGE_QUESTIONS = "challenge_questions"
    private const val CHALLENGE_INDEX = "challenge_index"
    private const val CHALLENGE_SESSION_ID = "challenge_session_id"
    private const val CHALLENGE_STARTED_AT = "challenge_started_at"
    private const val REUSE_CACHED_QUESTIONS = "reuse_cached_questions"
    private const val PENDING_ANSWER_ATTEMPTS = "pending_answer_attempts"
    private const val PENDING_CHALLENGE_RECORDS = "pending_challenge_records"

    const val STATUS_ACTIVE = "active"
    const val STATUS_ANSWERED = "answered"
    const val STATUS_TIMEOUT = "timeout"
    const val STATUS_REBOOT = "reboot"
    const val STATUS_DAILY_LIMIT = "daily_limit"

    fun all(context: Context): List<TvQuestion> {
        val answered = pendingCorrectAnswers(context).map { it.id }.toSet()
        val remote = ControlState.remoteQuestions(context)
        if (!remote.isNullOrBlank()) {
            runCatching { parseQuestions(remote) }
                .getOrNull()
                ?.let { return it.filterNot { question -> question.id in answered } }
        }
        val bundled = context.assets.open("questions.json").bufferedReader().use { it.readText() }
        return parseQuestions(bundled).filterNot { it.id in answered }
    }

    @Synchronized
    fun currentOrNext(context: Context): TvQuestion? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(CHALLENGE_ACTIVE, false)) {
            val cached = storedChallengeQuestions(preferences)
            val questions = if (preferences.getBoolean(REUSE_CACHED_QUESTIONS, false) && cached.size == CHALLENGE_QUESTION_COUNT) {
                cached
            } else {
                val available = all(context)
                if (available.size < CHALLENGE_QUESTION_COUNT) return null
                val previousId = preferences.getString(LAST_QUESTION_ID, null)
                val candidates = if (available.size > CHALLENGE_QUESTION_COUNT) {
                    available.filterNot { it.id == previousId }
                } else {
                    available
                }
                candidates.shuffled().take(CHALLENGE_QUESTION_COUNT)
            }
            val startedAt = System.currentTimeMillis()
            val sessionId = UUID.randomUUID().toString()
            check(preferences.edit()
                .putBoolean(CHALLENGE_ACTIVE, true)
                .putInt(CHALLENGE_ANSWER_COUNT, 0)
                .putInt(CHALLENGE_CORRECT_COUNT, 0)
                .putInt(CHALLENGE_INDEX, 0)
                .putString(CHALLENGE_SESSION_ID, sessionId)
                .putLong(CHALLENGE_STARTED_AT, startedAt)
                .putString(CHALLENGE_QUESTIONS, JSONArray(questions.map { toJsonObject(it) }).toString())
                .putBoolean(REUSE_CACHED_QUESTIONS, false)
                .commit()) { "Could not save the challenge questions." }
            queueChallengeRecord(context, STATUS_ACTIVE)
        }
        val questions = storedChallengeQuestions(preferences)
        return questions.getOrNull(preferences.getInt(CHALLENGE_INDEX, 0))
    }

    fun isChallengeActive(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(CHALLENGE_ACTIVE, false)

    fun challengeCorrectCount(context: Context): Int =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getInt(CHALLENGE_CORRECT_COUNT, 0)
            .coerceIn(0, CHALLENGE_QUESTION_COUNT)

    fun challengeAnsweredCount(context: Context): Int {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return preferences.getInt(CHALLENGE_ANSWER_COUNT, preferences.getInt(CHALLENGE_CORRECT_COUNT, 0))
            .coerceIn(0, CHALLENGE_QUESTION_COUNT)
    }

    @Synchronized
    fun finishChallenge(context: Context, status: String = STATUS_ANSWERED, interruptedQuestionId: String = "") {
        if (!isChallengeActive(context)) return
        queueChallengeRecord(context, status, interruptedQuestionId)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CHALLENGE_ACTIVE, false)
            .putInt(CHALLENGE_ANSWER_COUNT, 0)
            .putInt(CHALLENGE_CORRECT_COUNT, 0)
            .putInt(CHALLENGE_INDEX, 0)
            .putBoolean(REUSE_CACHED_QUESTIONS, false)
            .remove(CHALLENGE_SESSION_ID)
            .remove(CHALLENGE_STARTED_AT)
            .remove(CHALLENGE_QUESTIONS)
            .commit()
    }

    @Synchronized
    fun finishForReboot(context: Context) {
        if (!isChallengeActive(context)) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val questions = storedChallengeQuestions(preferences)
        val currentQuestion = questions.getOrNull(preferences.getInt(CHALLENGE_INDEX, 0))
        queueChallengeRecord(context, STATUS_REBOOT, currentQuestion?.id.orEmpty())
        check(preferences.edit()
            .putBoolean(CHALLENGE_ACTIVE, false)
            .putInt(CHALLENGE_ANSWER_COUNT, 0)
            .putInt(CHALLENGE_CORRECT_COUNT, 0)
            .putInt(CHALLENGE_INDEX, 0)
            .putBoolean(REUSE_CACHED_QUESTIONS, questions.size == CHALLENGE_QUESTION_COUNT)
            .remove(CHALLENGE_SESSION_ID)
            .remove(CHALLENGE_STARTED_AT)
            .commit()) { "Could not preserve the challenge questions after reboot." }
    }

    @Synchronized
    fun recordAnswer(context: Context, question: TvQuestion, selectedAnswer: String): ChallengeProgress {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val sessionId = checkNotNull(preferences.getString(CHALLENGE_SESSION_ID, null)) { "No active challenge session." }
        val correct = selectedAnswer == question.answer
        val answeredCount = (challengeAnsweredCount(context) + 1).coerceAtMost(CHALLENGE_QUESTION_COUNT)
        val correctCount = (challengeCorrectCount(context) + if (correct) 1 else 0)
            .coerceAtMost(CHALLENGE_QUESTION_COUNT)
        val answeredAt = System.currentTimeMillis()
        val pendingAnswers = pendingAnswerAttempts(context) + PendingAnswer(
            attemptId = UUID.randomUUID().toString(),
            challengeId = sessionId,
            question = question,
            selectedAnswer = selectedAnswer,
            answeredAt = answeredAt,
            date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(answeredAt)),
            answered = true,
        )
        val editor = preferences.edit()
            .putString(PENDING_ANSWER_ATTEMPTS, JSONArray(pendingAnswers.map { pendingAnswerToJson(it) }).toString())
            .putInt(CHALLENGE_ANSWER_COUNT, answeredCount)
            .putInt(CHALLENGE_CORRECT_COUNT, correctCount)
            .putInt(CHALLENGE_INDEX, preferences.getInt(CHALLENGE_INDEX, 0) + 1)
            .putString(LAST_QUESTION_ID, question.id)
        if (correct) {
            val pendingCorrect = pendingCorrectAnswers(context).filterNot { it.id == question.id } + question
            editor.putString(PENDING_CORRECT_ANSWERS, JSONArray(pendingCorrect.map { toJsonObject(it) }).toString())
        }
        check(editor.commit()) { "Could not save the challenge answer." }
        queueChallengeRecord(context, STATUS_ACTIVE)
        return ChallengeProgress(answeredCount, correctCount)
    }

    @Synchronized
    fun recordUnanswered(context: Context, question: TvQuestion) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val answeredAt = System.currentTimeMillis()
        val pendingAnswers = pendingAnswerAttempts(context) + PendingAnswer(
            attemptId = UUID.randomUUID().toString(),
            challengeId = checkNotNull(preferences.getString(CHALLENGE_SESSION_ID, null)) { "No active challenge session." },
            question = question,
            selectedAnswer = "",
            answeredAt = answeredAt,
            date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(answeredAt)),
            answered = false,
        )
        preferences.edit()
            .putString(PENDING_ANSWER_ATTEMPTS, JSONArray(pendingAnswers.map { pendingAnswerToJson(it) }).toString())
            .putString(LAST_QUESTION_ID, question.id)
            .commit()
    }

    @Synchronized
    fun pendingAnswerAttempts(context: Context): List<PendingAnswer> {
        val json = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(PENDING_ANSWER_ATTEMPTS, "[]") ?: "[]"
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PendingAnswer(
                attemptId = item.getString("attemptId"),
                challengeId = item.getString("challengeId"),
                question = parseQuestions(JSONArray().put(item.getJSONObject("question")).toString()).single(),
                selectedAnswer = item.getString("selectedAnswer"),
                answeredAt = item.getLong("answeredAt"),
                date = item.getString("date"),
                answered = item.optBoolean("answered", true),
            )
        }
    }

    @Synchronized
    fun acknowledgeAnswerAttempts(context: Context, ids: Set<String>) {
        val remaining = pendingAnswerAttempts(context).filterNot { it.attemptId in ids }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(PENDING_ANSWER_ATTEMPTS, JSONArray(remaining.map { pendingAnswerToJson(it) }).toString())
            .apply()
    }

    private fun pendingAnswerToJson(answer: PendingAnswer) = JSONObject()
        .put("attemptId", answer.attemptId)
        .put("challengeId", answer.challengeId)
        .put("question", toJsonObject(answer.question))
        .put("selectedAnswer", answer.selectedAnswer)
        .put("answeredAt", answer.answeredAt)
        .put("date", answer.date)
        .put("answered", answer.answered)

    @Synchronized
    fun pendingChallengeRecords(context: Context): List<PendingChallengeRecord> {
        val json = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(PENDING_CHALLENGE_RECORDS, "[]") ?: "[]"
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val questions = item.getJSONArray("questions")
            PendingChallengeRecord(
                sessionId = item.getString("sessionId"),
                date = item.getString("date"),
                startedAt = item.getLong("startedAt"),
                endedAt = item.getLong("endedAt"),
                status = item.getString("status"),
                answeredCount = item.getInt("answeredCount"),
                correctCount = item.getInt("correctCount"),
                questions = (0 until questions.length()).map { questionIndex ->
                    val question = questions.getJSONObject(questionIndex)
                    ChallengeQuestionSnapshot(question.getString("questionId"), question.getString("prompt"))
                },
                interruptedQuestionId = item.optString("interruptedQuestionId", ""),
            )
        }
    }

    @Synchronized
    fun acknowledgeChallengeRecords(context: Context, ids: Set<String>) {
        val remaining = pendingChallengeRecords(context).filterNot { it.sessionId in ids }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(PENDING_CHALLENGE_RECORDS, JSONArray(remaining.map { challengeRecordToJson(it) }).toString())
            .apply()
    }

    private fun queueChallengeRecord(context: Context, status: String, interruptedQuestionId: String = "") {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val sessionId = preferences.getString(CHALLENGE_SESSION_ID, null) ?: return
        val startedAt = preferences.getLong(CHALLENGE_STARTED_AT, 0L)
        val questions = storedChallengeQuestions(preferences)
        val record = PendingChallengeRecord(
            sessionId = sessionId,
            date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startedAt)),
            startedAt = startedAt,
            endedAt = if (status == STATUS_ACTIVE) 0L else System.currentTimeMillis(),
            status = status,
            answeredCount = challengeAnsweredCount(context),
            correctCount = challengeCorrectCount(context),
            questions = questions.map { ChallengeQuestionSnapshot(it.id, it.prompt) },
            interruptedQuestionId = interruptedQuestionId,
        )
        val pending = pendingChallengeRecords(context).filterNot { it.sessionId == sessionId } + record
        check(preferences.edit()
            .putString(PENDING_CHALLENGE_RECORDS, JSONArray(pending.map { challengeRecordToJson(it) }).toString())
            .commit()) { "Could not save the challenge record." }
    }

    private fun challengeRecordToJson(record: PendingChallengeRecord) = JSONObject()
        .put("sessionId", record.sessionId)
        .put("date", record.date)
        .put("startedAt", record.startedAt)
        .put("endedAt", record.endedAt)
        .put("status", record.status)
        .put("answeredCount", record.answeredCount)
        .put("correctCount", record.correctCount)
        .put("questions", JSONArray(record.questions.map { question ->
            JSONObject().put("questionId", question.questionId).put("prompt", question.prompt)
        }))
        .put("interruptedQuestionId", record.interruptedQuestionId)

    private fun storedChallengeQuestions(preferences: SharedPreferences): List<TvQuestion> {
        val json = preferences.getString(CHALLENGE_QUESTIONS, "[]") ?: "[]"
        return runCatching { parseQuestions(json) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun pendingCorrectAnswers(context: Context): List<TvQuestion> {
        val json = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(PENDING_CORRECT_ANSWERS, "[]") ?: "[]"
        return parseQuestions(json)
    }

    @Synchronized
    fun acknowledgeCorrectAnswers(context: Context, ids: Set<String>) {
        val remaining = pendingCorrectAnswers(context).filterNot { it.id in ids }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(PENDING_CORRECT_ANSWERS, JSONArray(remaining.map { toJsonObject(it) }).toString())
            .apply()
    }

    fun parseQuestions(json: String): List<TvQuestion> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val optionsJson = item.getJSONArray("options")
            val options = (0 until optionsJson.length()).map { optionsJson.getString(it) }
            val question = TvQuestion(
                id = item.getString("id"),
                prompt = item.getString("prompt"),
                options = options,
                answer = item.getString("answer"),
                category = item.optString("category", "Other"),
                difficulty = item.optString("difficulty", "Middle school"),
            )
            require(question.id.isNotBlank() && question.prompt.isNotBlank())
            require(question.options.size == 4 && question.options.distinct().size == 4)
            require(question.answer in question.options)
            question
        }
    }

    fun toJsonObject(question: TvQuestion): JSONObject = JSONObject()
        .put("id", question.id)
        .put("prompt", question.prompt)
        .put("options", JSONArray(question.options))
        .put("answer", question.answer)
        .put("category", question.category)
        .put("difficulty", question.difficulty)

    fun displayCategory(category: String): String = when (category) {
        "古诗词" -> "Classical poetry"
        "数学" -> "Math"
        "科学" -> "Science"
        "地理历史" -> "Geography & history"
        "其他" -> "Other"
        else -> category
    }
}
