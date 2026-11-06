package com.jarvis.control

/** Notes scan maths. Pure functions (no Android), so they run in the JVM unit tests. */
object NotesMath {
    /**
     * Share of the corrected words that the phone had already read correctly (each phone word counts once).
     * 1.0 = nothing needed fixing; 0.0 = nothing matched.
     */
    fun wordAccuracy(machine: String, corrected: String): Double {
        val want = corrected.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (want.isEmpty()) return 0.0
        val have = machine.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        var right = 0
        for (w in want) {
            val i = have.indexOf(w)
            if (i >= 0) {
                right++
                have.removeAt(i)
            }
        }
        return right.toDouble() / want.size
    }
}
