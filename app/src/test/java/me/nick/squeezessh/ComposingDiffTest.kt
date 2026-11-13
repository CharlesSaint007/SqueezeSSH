package me.nick.squeezessh

import com.termux.view.ComposingDiff
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposingDiffTest {
    private fun diff(old: String, new: String) = ComposingDiff.between(old, new).let { it.backspaces to it.insert }

    @Test
    fun typingAppendsOnlyTheNewLetters() = assertEquals(0 to "c", diff("ab", "abc"))

    @Test
    fun firstLetterIsJustInserted() = assertEquals(0 to "h", diff("", "h"))

    @Test
    fun correctionReplacesTheDifferingTail() = assertEquals(2 to "he", diff("teh", "the"))

    @Test
    fun aSuggestionLongerThanTheTypedWordAppends() = assertEquals(0 to "ere", diff("th", "there"))

    @Test
    fun deletingBacksUp() = assertEquals(2 to "", diff("word", "wo"))

    @Test
    fun identicalTextDoesNothing() = assertEquals(0 to "", diff("same", "same"))

    @Test
    fun anEmojiIsErasedWhole() = assertEquals(1 to "x", diff("😀", "x"))

    @Test
    fun anEmojiIsTypedWhole() = assertEquals(0 to "😀", diff("", "😀"))
}
