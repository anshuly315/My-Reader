@file:Suppress("ReturnCount")

package com.flowfolio.core.pdftools

/**
 * Page choices as people type them: `1-3, 5, 8-` (an open end runs to the last page), `all`, or `odd` / `even`.
 * Pages are 1-based; the result is in the order given, without repeats, and every page is within the document.
 */
object PageRanges {
    fun parse(spec: String, pageCount: Int): List<Int> {
        require(pageCount > 0) { "The document has no pages." }
        val text = spec.trim().lowercase()
        if (text.isEmpty() || text == "all") return (1..pageCount).toList()
        if (text == "odd") return (1..pageCount step 2).toList()
        if (text == "even") return (2..pageCount step 2).toList()
        val out = LinkedHashSet<Int>()
        for (part in text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val dash = part.indexOf('-')
            if (dash < 0) {
                out += single(part, pageCount)
            } else {
                val from = part.substring(0, dash).trim().ifEmpty { "1" }
                val to = part.substring(dash + 1).trim().ifEmpty { pageCount.toString() }
                val a = single(from, pageCount)
                val b = single(to, pageCount)
                if (a <= b) out.addAll(a..b) else out.addAll(a downTo b)
            }
        }
        require(out.isNotEmpty()) { "No pages chosen." }
        return out.toList()
    }

    private fun single(token: String, pageCount: Int): Int {
        val n = token.toIntOrNull() ?: throw IllegalArgumentException("\"$token\" is not a page number.")
        require(n in 1..pageCount) { "Page $n is not in this document (1 to $pageCount)." }
        return n
    }
}
