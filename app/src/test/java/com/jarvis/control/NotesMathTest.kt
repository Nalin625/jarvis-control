package com.jarvis.control

import org.junit.Assert.assertEquals
import org.junit.Test

/** Notes scan accuracy: the share of corrected words the phone had already read right. */
class NotesMathTest {

    @Test fun perfectReadingIsHundredPercent() {
        assertEquals(1.0, NotesMath.wordAccuracy("photosynthesis needs light", "photosynthesis needs light"), 1e-9)
    }

    @Test fun oneWrongWordOutOfThreeIsTwoThirds() {
        // 3 words in the corrected text, 2 of them read right first time
        assertEquals(2.0 / 3.0, NotesMath.wordAccuracy("photosynthesis needs lite", "photosynthesis needs light"), 1e-9)
    }

    @Test fun repeatedWordsCountOnce() {
        // the phone read "the" once and "cat" once; the corrected text has "the" twice, so 2 of 3 words count
        assertEquals(2.0 / 3.0, NotesMath.wordAccuracy("the cat", "the the cat"), 1e-9)
    }

    @Test fun emptyCorrectionScoresZero() {
        assertEquals(0.0, NotesMath.wordAccuracy("anything", "   "), 1e-9)
    }
}
