package io.github.xenonisawesome.rr.core

/**
 * Removes ANSI escape sequences from streamed text. A trace recorded outside the IDE (e.g. in CI) may contain
 * coloured gtest output (`ESC[0;32m[ RUN      ] ESC[mFoo.Bar`); CLion's test parser only recognises the plain tags.
 * Chunks may split a sequence, so an unfinished one at the end is held back until the next call.
 */
class AnsiStripper {
    private var carry = ""

    @Synchronized
    fun strip(chunk: String): String {
        val text = carry + chunk
        carry = ""
        val cut = text.lastIndexOf('\u001B')
        val body = if (cut >= 0 && text.length - cut < 32 && !SEQUENCE_END.containsMatchIn(text.substring(cut + 1))) {
            carry = text.substring(cut)
            text.substring(0, cut)
        } else text
        return ANSI.replace(body, "")
    }

    companion object {
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[@-Z\\\\-_]")
        private val SEQUENCE_END = Regex("^\\[[0-9;?]*[ -/]*[@-~]|^[@-Z\\\\-_]")
    }
}
