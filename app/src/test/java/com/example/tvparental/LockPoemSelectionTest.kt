package com.example.tvparental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPoemSelectionTest {
    private val firstPoem = Poem("登鹳雀楼", "王之涣", "白日依山尽，黄河入海流。\n欲穷千里目，更上一层楼。")
    private val secondPoem = Poem("小池", "杨万里", "泉眼无声惜细流，树阴照水爱晴柔。\n小荷才露尖尖角，早有蜻蜓立上头。")

    private fun question(id: String, poem: Poem? = null, category: String = "古诗词") = TvQuestion(
        id, "Question $id", listOf("A", "B", "C", "D"), "A", category, "Middle school", poem = poem,
    )

    @Test
    fun anIncorrectPoetryWorkTakesPriorityOverTheRestOfTheBank() {
        val incorrect = question("wrong", firstPoem)
        val bank = listOf(incorrect, question("other", secondPoem))
        repeat(20) {
            assertEquals(firstPoem, QuestionBank.selectLockoutPoem(listOf(incorrect), bank, emptyList()))
        }
    }

    @Test
    fun selectionStaysWithinThisChallengesIncorrectWorks() {
        val incorrect = listOf(question("one", firstPoem), question("two", secondPoem))
        val other = question("three", Poem("其他", "作者", "完整正文"))
        repeat(30) {
            assertTrue(QuestionBank.selectLockoutPoem(incorrect, incorrect + other, emptyList()) in setOf(firstPoem, secondPoem))
        }
    }

    @Test
    fun aCachedQuestionCanUseNewlySyncedFullTextForTheSameQuestion() {
        val cached = question("one")
        assertEquals(firstPoem, QuestionBank.selectLockoutPoem(
            listOf(cached), listOf(cached.copy(poem = firstPoem)), emptyList(),
        ))
    }

    @Test
    fun aReusedIdCannotReplaceTheCachedQuestionsWork() {
        val cached = question("one")
        val replacement = cached.copy(prompt = "A different question", poem = secondPoem)
        repeat(20) {
            assertEquals(firstPoem, QuestionBank.selectLockoutPoem(
                listOf(cached, question("two", firstPoem)), listOf(replacement), emptyList(),
            ))
        }
    }

    @Test
    fun aCachedWorkSurvivesDeletionFromTheRemoteBank() {
        assertEquals(firstPoem, QuestionBank.selectLockoutPoem(
            listOf(question("removed", firstPoem)), emptyList(), listOf(question("fallback", secondPoem)),
        ))
    }

    @Test
    fun noIncorrectPoetryUsesABankWorkBeforeTheBundledFallback() {
        val math = question("math", category = "数学")
        assertEquals(secondPoem, QuestionBank.selectLockoutPoem(
            listOf(math), listOf(math, question("bank", secondPoem)), listOf(question("fallback", firstPoem)),
        ))
    }

    @Test
    fun nonPoetryMetadataCannotOverrideThePoetryBank() {
        val math = question("math", firstPoem, "数学")
        assertEquals(secondPoem, QuestionBank.selectLockoutPoem(
            listOf(math), listOf(math, question("bank", secondPoem)), emptyList(),
        ))
    }

    @Test
    fun aLegacyBundledQuestionUsesItsOwnWorkBeforeARandomFallback() {
        val cached = question("one")
        val bundled = listOf(cached.copy(poem = firstPoem), question("other", secondPoem))
        repeat(20) {
            assertEquals(firstPoem, QuestionBank.selectLockoutPoem(listOf(cached), emptyList(), bundled))
        }
    }

    @Test
    fun missingRemoteFullTextsUseTheOfflineBundledWork() {
        assertEquals(firstPoem, QuestionBank.selectLockoutPoem(
            listOf(question("legacy")), listOf(question("legacy")), listOf(question("fallback", firstPoem)),
        ))
        assertNull(QuestionBank.selectLockoutPoem(emptyList(), emptyList(), emptyList()))
    }
}
