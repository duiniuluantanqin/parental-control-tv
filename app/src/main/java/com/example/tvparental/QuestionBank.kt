package com.example.tvparental

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TvQuestion(
    val id: String,
    val prompt: String,
    val options: List<String>,
    val answer: String,
    val category: String,
    val difficulty: String,
)

object QuestionBank {
    const val CHALLENGE_QUESTION_COUNT = 5
    private const val PREFERENCES = "question_state"
    private const val LAST_QUESTION_ID = "last_question_id"
    private const val CURRENT_QUESTION_ID = "current_question_id"
    private const val PENDING_CORRECT_ANSWERS = "pending_correct_answers"
    private const val CHALLENGE_ACTIVE = "challenge_active"
    private const val CHALLENGE_CORRECT_COUNT = "challenge_correct_count"

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

    fun restartChallenge(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CHALLENGE_ACTIVE, true)
            .putInt(CHALLENGE_CORRECT_COUNT, 0)
            .remove(CURRENT_QUESTION_ID)
            .apply()
    }

    fun finishChallenge(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CHALLENGE_ACTIVE, false)
            .putInt(CHALLENGE_CORRECT_COUNT, 0)
            .remove(CURRENT_QUESTION_ID)
            .apply()
    }

    @Synchronized
    fun markCorrect(context: Context, question: TvQuestion): Int {
        val pending = pendingCorrectAnswers(context).filterNot { it.id == question.id } + question
        val correctCount = (challengeCorrectCount(context) + 1).coerceAtMost(CHALLENGE_QUESTION_COUNT)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(PENDING_CORRECT_ANSWERS, JSONArray(pending.map { toJsonObject(it) }).toString())
            .putInt(CHALLENGE_CORRECT_COUNT, correctCount)
            .remove(CURRENT_QUESTION_ID)
            .apply()
        return correctCount
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
