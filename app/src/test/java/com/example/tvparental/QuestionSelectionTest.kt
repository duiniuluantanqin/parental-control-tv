package com.example.tvparental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionSelectionTest {
    private fun question(id: Int, priority: Boolean = false) = TvQuestion(
        id = id.toString(), prompt = "Question $id", options = listOf("A", "B", "C", "D"),
        answer = "A", category = "Math", difficulty = "Medium", priority = priority,
    )

    @Test
    fun enoughPriorityQuestionsExcludeRegularQuestions() {
        val available = (1..8).map { question(it, true) } + (9..20).map { question(it) }
        val selected = QuestionBank.selectQuestions(available, 5)
        assertEquals(5, selected.size)
        assertTrue(selected.all { it.priority })
        assertEquals(5, selected.map { it.id }.distinct().size)
    }

    @Test
    fun regularQuestionsFillTheRemainingSlotsAfterPriorityQuestions() {
        val priority = listOf(question(1, true), question(2, true))
        val available = (3..12).map { question(it) } + priority
        val selected = QuestionBank.selectQuestions(available, 5)
        assertEquals(priority.toSet(), selected.take(2).toSet())
        assertTrue(selected.drop(2).none { it.priority })
        assertEquals(5, selected.map { it.id }.distinct().size)
    }

    @Test
    fun noPriorityQuestionsStillProduceAFullChallenge() {
        val available = (1..10).map { question(it) }
        val selected = QuestionBank.selectQuestions(available, 5)
        assertEquals(5, selected.size)
        assertTrue(selected.all { it in available })
        assertFalse(selected.any { it.priority })
        assertEquals(5, selected.toSet().size)
    }

    @Test
    fun singleTestQuestionAlsoUsesPriorityAndHandlesAnEmptyBank() {
        val priority = question(1, true)
        assertEquals(listOf(priority), QuestionBank.selectQuestions(listOf(question(2), priority), 1))
        assertTrue(QuestionBank.selectQuestions(emptyList(), 1).isEmpty())
    }

    @Test
    fun avoidingThePreviousQuestionDoesNotDiscardAnyOfFivePriorityQuestions() {
        val priority = (1..5).map { question(it, true) }
        val available = priority + (6..15).map { question(it) }
        val selected = QuestionBank.selectQuestions(available, 5, previousId = "1")
        assertTrue(selected.first().id != "1")
        assertEquals(priority.toSet(), selected.toSet())
    }

    @Test
    fun onlyPriorityQuestionCanFollowARegularQuestionToAvoidAnImmediateRepeat() {
        val priority = question(1, true)
        val selected = QuestionBank.selectQuestions(listOf(priority) + (2..8).map { question(it) }, 5, previousId = "1")
        assertTrue(selected.first().id != "1")
        assertEquals(priority, selected[1])
        assertEquals(5, selected.toSet().size)
    }
}
