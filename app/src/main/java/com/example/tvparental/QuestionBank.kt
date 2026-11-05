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
    val question: TvQuestion,
    val selectedAnswer: String,
    val answeredAt: Long,
    val date: String,
    val answered: Boolean = true,
)

object QuestionBank {
    const val CHALLENGE_QUESTION_COUNT = 5
    const val CHALLENGE_REQUIRED_CORRECT = 4
    private const val PREFERENCES = "question_state"
    private const val LAST_QUESTION_ID = "last_question_id"
    private const val CURRENT_QUESTION_ID = "current_question_id"
    private const val PENDING_CORRECT_ANSWERS = "pending_correct_answers"
    private const val CHALLENGE_ACTIVE = "challenge_active"
    private const val CHALLENGE_ANSWER_COUNT = "challenge_answer_count"
    private const val CHALLENGE_CORRECT_COUNT = "challenge_correct_count"
    private const val PENDING_ANSWER_ATTEMPTS = "pending_answer_attempts"

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

    fun currentOrNext(context: Context): TvQuestion? {
        val questions = all(context)
        if (questions.isEmpty()) return null
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val currentId = questionId(preferences, CURRENT_QUESTION_ID)
        questions.firstOrNull { it.id == currentId }?.let { return it }
        val previousId = questionId(preferences, LAST_QUESTION_ID)
        val candidates = if (questions.size > 1) questions.filterNot { it.id == previousId } else questions
        val question = candidates.random()
        preferences.edit()
            .putString(LAST_QUESTION_ID, question.id)
            .putString(CURRENT_QUESTION_ID, question.id)
            .apply()
        return question
    }

    private fun questionId(preferences: SharedPreferences, key: String): String? {
        // Older releases saved integer IDs. getString throws on those values after an upgrade.
        return when (val value = preferences.all[key]) {
            is String -> value
            is Int -> value.toString().also { id ->
                preferences.edit().putString(key, id).apply()
            }
            else -> null
        }
    }

    fun beginChallenge(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CHALLENGE_ACTIVE, true)
            .apply()
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

    fun restartChallenge(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CHALLENGE_ACTIVE, true)
            .putInt(CHALLENGE_ANSWER_COUNT, 0)
            .putInt(CHALLENGE_CORRECT_COUNT, 0)
            .remove(CURRENT_QUESTION_ID)
            .apply()
    }

    fun finishChallenge(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CHALLENGE_ACTIVE, false)
            .putInt(CHALLENGE_ANSWER_COUNT, 0)
            .putInt(CHALLENGE_CORRECT_COUNT, 0)
            .remove(CURRENT_QUESTION_ID)
            .apply()
    }

    @Synchronized
    fun recordAnswer(context: Context, question: TvQuestion, selectedAnswer: String): ChallengeProgress {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val correct = selectedAnswer == question.answer
        val answeredCount = (challengeAnsweredCount(context) + 1).coerceAtMost(CHALLENGE_QUESTION_COUNT)
        val correctCount = (challengeCorrectCount(context) + if (correct) 1 else 0)
            .coerceAtMost(CHALLENGE_QUESTION_COUNT)
        val answeredAt = System.currentTimeMillis()
        val pendingAnswers = pendingAnswerAttempts(context) + PendingAnswer(
            attemptId = UUID.randomUUID().toString(),
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
            .remove(CURRENT_QUESTION_ID)
        if (correct) {
            val pendingCorrect = pendingCorrectAnswers(context).filterNot { it.id == question.id } + question
            editor.putString(PENDING_CORRECT_ANSWERS, JSONArray(pendingCorrect.map { toJsonObject(it) }).toString())
        }
        editor.apply()
        return ChallengeProgress(answeredCount, correctCount)
    }

    @Synchronized
    fun recordUnanswered(context: Context, question: TvQuestion) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val answeredAt = System.currentTimeMillis()
        val pendingAnswers = pendingAnswerAttempts(context) + PendingAnswer(
            attemptId = UUID.randomUUID().toString(),
            question = question,
            selectedAnswer = "",
            answeredAt = answeredAt,
            date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(answeredAt)),
            answered = false,
        )
        preferences.edit()
            .putString(PENDING_ANSWER_ATTEMPTS, JSONArray(pendingAnswers.map { pendingAnswerToJson(it) }).toString())
            .remove(CURRENT_QUESTION_ID)
            .apply()
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
        .put("question", toJsonObject(answer.question))
        .put("selectedAnswer", answer.selectedAnswer)
        .put("answeredAt", answer.answeredAt)
        .put("date", answer.date)
        .put("answered", answer.answered)

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
