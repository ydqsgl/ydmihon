package eu.kanade.tachiyomi.extension.zh.common

/**
 * Unpacks a Dean Edwards "packer" script (`eval(function(p,a,c,k,e,d){…}(…))`).
 *
 * Several of these sites ship their player bootstrap through the packer, which is why the stream
 * URL is not visible in the HTML at all: the page only carries a dictionary of words and a body in
 * which every word is replaced by its index written in base `a`. Rebuilding the body from the
 * dictionary is enough to read the URLs back out; the result is never executed.
 *
 * Only the first packed block on the page is unpacked, which is the player script on every site
 * seen so far.
 */
internal object JsPacker {

    fun unpack(source: String): String? {
        val start = source.indexOf(MARKER)
        if (start < 0) return null

        val bodyEnd = source.indexOf(RETURN_STATEMENT, start)
        if (bodyEnd < 0) return null

        val argsStart = source.indexOf('(', bodyEnd + RETURN_STATEMENT.length)
        if (argsStart < 0) return null

        // First argument: the packed body, a single-quoted string.
        val payload = readQuoted(source, argsStart + 1) ?: return null

        // Then `,a,c,'word|word|…'.split('|')`.
        val numbers = NUMBERS.find(source.substring(payload.end)) ?: return null
        val base = numbers.groupValues[1].toIntOrNull() ?: return null
        val count = numbers.groupValues[2].toIntOrNull() ?: return null
        if (base < 2 || count <= 0) return null

        val wordsStart = payload.end + numbers.range.last + 1
        val words = readQuoted(source, wordsStart)?.value?.split('|') ?: return null

        var result = payload.value
        // Highest index first, matching the original `while (c--)`: a substituted word can itself
        // look like an index, and descending order guarantees it is never rewritten.
        for (index in count - 1 downTo 0) {
            val word = words.getOrNull(index).orEmpty()
            if (word.isEmpty()) continue
            val token = index.toString(base)
            result = Regex("\\b${Regex.escape(token)}\\b").replace(result, Regex.escapeReplacement(word))
        }
        return result
    }

    private class Quoted(val value: String, val end: Int)

    /** Reads a single- or double-quoted JS string starting at [from]; [end] is past the closing quote. */
    private fun readQuoted(source: String, from: Int): Quoted? {
        if (from >= source.length) return null
        val quote = source[from]
        if (quote != '\'' && quote != '"') return null

        val builder = StringBuilder()
        var index = from + 1
        while (index < source.length) {
            val char = source[index]
            when {
                char == '\\' && index + 1 < source.length -> {
                    builder.append(char).append(source[index + 1])
                    index += 2
                }
                char == quote -> return Quoted(builder.toString(), index + 1)
                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        return null
    }

    private const val MARKER = "eval(function(p,a,c,k,e,d)"
    private const val RETURN_STATEMENT = "return p}"

    private val NUMBERS = Regex("""^\s*,\s*(\d+)\s*,\s*(\d+)\s*,""")
}
