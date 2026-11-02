package com.example.tvparental

import android.content.Context
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
    private const val PREFERENCES = "question_state"
    private const val LAST_QUESTION_ID = "last_question_id"
    private const val CURRENT_QUESTION_ID = "current_question_id"

    fun all(context: Context): List<TvQuestion> {
        val remote = ControlState.remoteQuestions(context)
        if (!remote.isNullOrBlank()) {
            runCatching { parseQuestions(remote) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        val bundled = context.assets.open("questions.json").bufferedReader().use { it.readText() }
        return parseQuestions(bundled)
    }

    fun currentOrNext(context: Context): TvQuestion {
        val questions = all(context)
        require(questions.isNotEmpty()) { "题库不能为空" }
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val currentId = preferences.getString(CURRENT_QUESTION_ID, null)
        questions.firstOrNull { it.id == currentId }?.let { return it }
        val previousId = preferences.getString(LAST_QUESTION_ID, null)
        val candidates = if (questions.size > 1) questions.filterNot { it.id == previousId } else questions
        val question = candidates.random()
        preferences.edit()
            .putString(LAST_QUESTION_ID, question.id)
            .putString(CURRENT_QUESTION_ID, question.id)
            .apply()
        return question
    }

    fun finishCurrent(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(CURRENT_QUESTION_ID)
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
                category = item.optString("category", "其他"),
                difficulty = item.optString("difficulty", "初中"),
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
}
