package com.mobileaiagent.tools.builtin

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.providers.transport.HttpTransport
import com.mobileaiagent.tools.spec.*

/**
 * Web research tools backed by the shared HTTP transport.
 *
 * `web_search` uses DuckDuckGo's HTML endpoint by default (no API key required)
 * and supports a user-configured endpoint (Brave/Tavily/SearxNG) via
 * Settings → Research. When neither is reachable it fails honestly instead of
 * hallucinating results.
 *
 * `web_fetch` downloads a page and converts it to readable text. No JavaScript
 * execution: SPA pages that render client-side will return little text, and the
 * result says so.
 */
class WebSearchTool(private val transport: HttpTransport) : AgentTool {
    override val id = "web_search"
    override val name = "Web search"
    override val description =
        "Search the public web and return ranked results (title, url, snippet). " +
            "Uses DuckDuckGo HTML by default or a configured search endpoint."
    override val version = "1.0.0"
    override val category = ToolCategory.RESEARCH
    override val inputSchema = schema {
        requiredString("query", "Search query.")
        optionalInt("max_results", "1-15 results (default 8).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.NETWORK)
    override val mutagenic = false
    override val cancellationSupported = true
    override val maxResultChars = 16_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        if (!ctx.settingsSnapshot.internetAccess) {
            return ToolResult.Failure("Internet access is disabled in settings", errorKind = "disabled")
        }
        val args = ToolArgs(input)
        val query = args.string("query").trim()
        val max = args.int("max_results", required = false, default = 8).coerceIn(1, 15)
        val endpoint = ctx.settingsSnapshot.searchEndpoint
        ctx.emitProgress("Searching the web…", null)
        val result = try {
            if (!endpoint.isNullOrBlank() && endpoint.contains("{query}")) {
                val url = endpoint.replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
                transport.get(url, mapOf("Accept" to "application/json, text/html"), cancellation = ctx.cancellation)
            } else {
                transport.get(
                    "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8"),
                    mapOf(
                        "Accept" to "text/html",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 MobileAIAgent/1.0",
                    ),
                    cancellation = ctx.cancellation,
                )
            }
        } catch (e: Exception) {
            return ToolResult.Failure("Search request failed: ${e.message}", retryable = true, errorKind = "network")
        }
        if (!result.isSuccess) {
            return ToolResult.Failure("Search returned HTTP ${result.statusCode}. ${result.body.take(300)}", retryable = true, errorKind = "network")
        }
        val hits = tryParseJsonHits(result.body) ?: HtmlText.parseDuckDuckGoHtml(result.body)
        if (hits.isEmpty()) {
            return ToolResult.Failure(
                "Search returned no parseable results (HTTP ${result.statusCode}). " +
                    "The search provider may be rate-limited; try a different query or configure a search API endpoint in Settings.",
                retryable = true, errorKind = "empty_results",
            )
        }
        val sb = StringBuilder("Search results for \"$query\":\n")
        hits.take(max).forEachIndexed { i, hit ->
            sb.appendLine()
            sb.appendLine("${i + 1}. ${hit.title}")
            sb.appendLine("   ${hit.url}")
            if (hit.snippet.isNotBlank()) sb.appendLine("   ${hit.snippet.take(300)}")
        }
        return ToolResult.Success(
            sb.toString(),
            structured = JsonValue.Arr(
                hits.take(max).map {
                    JsonValue.obj(
                        "title" to JsonValue.of(it.title),
                        "url" to JsonValue.of(it.url),
                        "snippet" to JsonValue.of(it.snippet),
                    )
                }
            ),
        )
    }

    private fun tryParseJsonHits(body: String): List<HtmlText.SearchHit>? {
        val json = com.mobileaiagent.core.json.JsonParser.parseOrNull(body) ?: return null
        val array = json["results"].asArrayOrNull() ?: json["web"]?.let { it["results"].asArrayOrNull() }
            ?: json.asArrayOrNull() ?: return null
        val hits = array.mapNotNull { r ->
            val url = r["url"].asStringOrNull() ?: r["link"].asStringOrNull() ?: return@mapNotNull null
            val title = r["title"].asStringOrNull() ?: r["name"].asStringOrNull() ?: url
            val snippet = r["snippet"].asStringOrNull() ?: r["description"].asStringOrNull() ?: ""
            HtmlText.SearchHit(title, url, snippet)
        }
        return hits.ifEmpty { null }
    }
}

class WebFetchTool(private val transport: HttpTransport) : AgentTool {
    override val id = "web_fetch"
    override val name = "Fetch web page"
    override val description =
        "Download a URL and extract readable text (title, body, links). No JavaScript execution; " +
            "client-rendered pages may return little text. Large responses are truncated."
    override val version = "1.0.0"
    override val category = ToolCategory.RESEARCH
    override val inputSchema = schema {
        requiredString("url", "Absolute http(s) URL.")
        optionalInt("max_chars", "Max characters of extracted text (default 16000, cap 60000).")
        optionalBool("include_links", "Include extracted links (default false).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.NETWORK)
    override val mutagenic = false
    override val cancellationSupported = true
    override val maxResultChars = 64_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        if (!ctx.settingsSnapshot.internetAccess) {
            return ToolResult.Failure("Internet access is disabled in settings", errorKind = "disabled")
        }
        val args = ToolArgs(input)
        val url = args.string("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.Failure("Only http(s) URLs are supported", errorKind = "invalid_arguments")
        }
        val maxChars = args.int("max_chars", required = false, default = 16_000).coerceIn(500, 60_000)
        ctx.emitProgress("Fetching ${url.take(60)}…", null)
        val response = try {
            transport.get(
                url,
                mapOf(
                    "Accept" to "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) MobileAIAgent/1.0",
                ),
                timeoutMs = 45_000,
                cancellation = ctx.cancellation,
            )
        } catch (e: Exception) {
            return ToolResult.Failure("Fetch failed: ${e.message}", retryable = true, errorKind = "network")
        }
        if (!response.isSuccess) {
            return ToolResult.Failure("HTTP ${response.statusCode} for $url", retryable = response.statusCode in 500..599, errorKind = "http")
        }
        val contentType = response.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: ""
        val body = response.body
        if (body.length > 4_000_000) {
            return ToolResult.Failure("Response too large (${body.length} chars)", errorKind = "too_large")
        }
        if (contentType.contains("json")) {
            return ToolResult.Success("URL: $url (JSON)\n\n" + body.take(maxChars))
        }
        if (!contentType.contains("html") && !body.trimStart().startsWith("<")) {
            return ToolResult.Success("URL: $url (text)\n\n" + body.take(maxChars))
        }
        val extracted = HtmlText.extract(body, maxChars)
        val sb = StringBuilder()
        sb.appendLine("URL: $url")
        if (extracted.title.isNotBlank()) sb.appendLine("Title: ${extracted.title}")
        sb.appendLine("---")
        sb.appendLine(extracted.text)
        if (extracted.text.length < 200) {
            sb.appendLine()
            sb.appendLine("(Note: very little text extracted — the page likely renders with JavaScript.)")
        }
        if (args.boolean("include_links", false) && extracted.links.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Links:")
            extracted.links.take(60).forEach { (href, label) -> sb.appendLine("- $label → $href") }
        }
        return ToolResult.Success(sb.toString())
    }
}
