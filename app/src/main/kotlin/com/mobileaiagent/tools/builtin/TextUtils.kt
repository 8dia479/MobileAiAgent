package com.mobileaiagent.tools.builtin

/**
 * HTML-to-text extraction used by web_fetch and web_search results.
 * Not a browser engine: strips scripts/styles/tags, decodes common entities and
 * preserves block structure. Honest about its limits (no JS rendering).
 */
object HtmlText {

    data class Extracted(val title: String, val text: String, val links: List<Pair<String, String>>)

    fun extract(html: String, maxChars: Int = 24_000): Extracted {
        val withoutScripts = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<noscript.*?</noscript>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<svg.*?</svg>"), " ")
            .replace(Regex("(?is)<head.*?</head>"), " ")

        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            ?.groupValues?.get(1)?.let { decodeEntities(it.trim()) } ?: ""

        val links = mutableListOf<Pair<String, String>>()
        Regex("(?is)<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>").findAll(withoutScripts).forEach { m ->
            val href = m.groupValues[1].trim()
            val label = m.groupValues[2].replace(Regex("<[^>]+>"), " ").trim()
            if (href.startsWith("http") && label.isNotBlank() && links.size < 80) {
                links.add(href to decodeEntities(label).replace(Regex("\\s+"), " ").take(120))
            }
        }

        val withBreaks = withoutScripts
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|h[1-6]|tr|section|article|header|footer|blockquote|pre)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "- ")
            .replace(Regex("(?i)<(td|th)[^>]*>"), " | ")
            .replace(Regex("(?is)<[^>]+>"), " ")

        val text = decodeEntities(withBreaks)
            .replace(Regex("[ \\t\\u00a0]+"), " ")
            .replace(Regex("\n\\s*\n\\s*"), "\n\n")
            .trim()
            .take(maxChars)
        return Extracted(title, text, links)
    }

    fun decodeEntities(input: String): String {
        var s = input
        commonEntities.forEach { (entity, char) -> s = s.replace(entity, char) }
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: ""
        }
        s = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: ""
        }
        return s
    }

    private val commonEntities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
        "&rsquo;" to "’", "&lsquo;" to "‘", "&ldquo;" to "“", "&rdquo;" to "”", "&copy;" to "©",
        "&trade;" to "™", "&reg;" to "®", "&deg;" to "°", "&plusmn;" to "±", "&times;" to "×",
    )

    /** Extracts a JSON-ish array of search results from DuckDuckGo HTML output. */
    data class SearchHit(val title: String, val url: String, val snippet: String)

    fun parseDuckDuckGoHtml(html: String): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        val resultRegex = Regex(
            "(?is)<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?" +
                "(?:<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>|<div[^>]+class=\"result__snippet\"[^>]*>(.*?)</div>)?",
        )
        for (m in resultRegex.findAll(html)) {
            val url = decodeEntities(m.groupValues[1]).let(::cleanDdgUrl)
            val title = decodeEntities(m.groupValues[2].replace(Regex("<[^>]+>"), "")).trim()
            val snippetRaw = m.groupValues[3].ifBlank { m.groupValues[4] }
            val snippet = decodeEntities(snippetRaw.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
            if (url.startsWith("http") && title.isNotBlank()) {
                hits.add(SearchHit(title, url, snippet))
            }
            if (hits.size >= 20) break
        }
        return hits
    }

    /** DuckDuckGo wraps results in /l/?uddg=<encoded>; unwrap it. */
    fun cleanDdgUrl(raw: String): String {
        val decoded = raw.trim()
        val uddgIndex = decoded.indexOf("uddg=")
        if (uddgIndex >= 0) {
            val encoded = decoded.substring(uddgIndex + 5).substringBefore('&')
            return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(decoded)
        }
        return decoded
    }
}
