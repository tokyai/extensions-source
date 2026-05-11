package eu.kanade.tachiyomi.extension.zh.laimanhua

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

class LaiManhua :
    HttpSource(),
    ConfigurableSource {

    override val name = "来漫画"

    override val baseUrl = DESKTOP_BASE_URL

    override val lang = "zh"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val coverFallbackMangaUrls = ConcurrentHashMap<String, String>()

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::coverImageHeaderInterceptor)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$activeBaseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "zh-CN,zh;q=0.9")

    private val activeBaseUrl: String
        get() = when (activeParseMode) {
            ParseMode.DESKTOP -> DESKTOP_BASE_URL
            ParseMode.MOBILE -> MOBILE_BASE_URL
        }

    private val activeParseMode: ParseMode
        get() = ParseMode.fromPreference(preferences.getString(PARSE_MODE_PREF, DEFAULT_PARSE_MODE_VALUE))

    private val requestHeaders: Headers
        get() = headersBuilder().build()

    override fun mangaDetailsRequest(manga: SManga): Request = GET(activeBaseUrl + manga.url, requestHeaders)

    override fun chapterListRequest(manga: SManga): Request = GET(activeBaseUrl + manga.url, requestHeaders)

    override fun pageListRequest(chapter: SChapter): Request = GET(activeBaseUrl + chapter.url, requestHeaders)

    override fun popularMangaRequest(page: Int): Request {
        val url = when (activeParseMode) {
            ParseMode.DESKTOP -> "$DESKTOP_BASE_URL/kanmanhua/zaixian_hit.html"
            ParseMode.MOBILE -> "$MOBILE_BASE_URL/kanmanhua/zaixian_hit.html"
        }
        return GET(url, requestHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asGbkJsoup()
        val mangas = when (activeParseMode) {
            ParseMode.DESKTOP -> document.select("a.vtip[href^=/kanmanhua/]")
                .map { it.toDesktopRankManga() }
                .distinctBy { it.url }
            ParseMode.MOBILE -> document.select(".cont-list li > a[href^=/kanmanhua/]")
                .map { it.toMobileListManga() }
                .distinctBy { it.url }
        }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = when (activeParseMode) {
        ParseMode.DESKTOP -> GET("$DESKTOP_BASE_URL/kanmanhua/zaixian_recent.html", requestHeaders)
        ParseMode.MOBILE -> {
            if (page == 1) {
                GET("$MOBILE_BASE_URL/kanmanhua/zaixian_recent.html", requestHeaders)
            } else {
                val url = "$MOBILE_BASE_URL/getact2.asp".toHttpUrl().newBuilder()
                    .addQueryParameter("act", "list")
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("catid", "0")
                    .addQueryParameter("ajax", "1")
                    .addQueryParameter("order", "1")
                    .build()
                GET(url, requestHeaders)
            }
        }
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asGbkJsoup()
        return when (activeParseMode) {
            ParseMode.DESKTOP -> MangasPage(
                document.select(".updateList li").mapNotNull { it.toDesktopUpdateManga() },
                false,
            )
            ParseMode.MOBILE -> MangasPage(
                document.select(".cont-list li > a[href^=/kanmanhua/], body > li > a[href^=/kanmanhua/]").map { it.toMobileListManga() },
                document.selectFirst("#more") != null || response.request.url.encodedPath.endsWith("/getact2.asp"),
            )
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) {
            return latestUpdatesRequest(page)
        }

        val encodedQuery = URLEncoder.encode(query, gbkCharset().name())
        return GET("$DESKTOP_BASE_URL/s81/search/?key=$encodedQuery", requestHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asGbkJsoup()
        return MangasPage(
            document.select("#dmList li").mapNotNull { it.toDesktopSearchManga() },
            document.selectFirst("#pager a:contains(下一页)") != null,
        )
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asGbkJsoup()
        return when (activeParseMode) {
            ParseMode.DESKTOP -> document.toDesktopDetails(response.request.url.toString())
            ParseMode.MOBILE -> document.toMobileDetails(response.request.url.toString())
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asGbkJsoup()
        val selector = when (activeParseMode) {
            ParseMode.DESKTOP -> "#play_0 a[href$=.html]"
            ParseMode.MOBILE -> "#chapterList a[href$=.html]"
        }
        return document.select(selector).map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = element.attr("title").ifBlank { element.text() }.trim()
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.asGbkString()
        val imageUrls = when (activeParseMode) {
            ParseMode.DESKTOP -> parseDesktopImages(html)
            ParseMode.MOBILE -> parseMobileImages(html)
        }
        val referer = response.request.url.toString()
        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, referer, imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val referer = page.url.ifBlank { "$activeBaseUrl/" }
        val imageHeaders = headersBuilder()
            .set("Referer", referer)
            .set("Accept", IMAGE_ACCEPT)
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PARSE_MODE_PREF
            title = "页面解析方式"
            summary = "%s\n切换后重新进入漫画详情或重新搜索生效"
            entries = arrayOf("桌面页解析", "移动页解析")
            entryValues = arrayOf(ParseMode.DESKTOP.preferenceValue, ParseMode.MOBILE.preferenceValue)
            setDefaultValue(DEFAULT_PARSE_MODE_VALUE)
        }.also(screen::addPreference)
    }

    private fun coverImageHeaderInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val shouldPatch = request.url.host.isKnownImageHost() || request.url.encodedPath.isLikelyImagePath()
        if (!shouldPatch) {
            return chain.proceed(request)
        }

        val newRequest = request.newBuilder().apply {
            if (request.header("User-Agent") == null) {
                header("User-Agent", USER_AGENT)
            }
            if (request.header("Referer") == null) {
                header("Referer", "$activeBaseUrl/")
            }
            if (request.header("Accept") == null) {
                header("Accept", IMAGE_ACCEPT)
            }
        }.build()

        val response = chain.proceed(newRequest)
        if (!newRequest.isRegisteredCoverRequest() || !response.hasHtmlBody()) {
            return response
        }

        val fallbackRequest = newRequest.fallbackCoverRequest() ?: return response
        response.close()
        return chain.proceed(fallbackRequest)
    }

    private fun Request.isRegisteredCoverRequest(): Boolean {
        val requestUrl = url.toString()
        return coverFallbackMangaUrls.containsKey(requestUrl) ||
            coverFallbackMangaUrls.containsKey(requestUrl.removeSuffix(THUMBNAIL_SUFFIX))
    }

    private fun Response.hasHtmlBody(): Boolean {
        val sample = peekBody(64).bytes().dropWhile { it.toInt() <= 0x20 }
        return sample.firstOrNull() == '<'.code.toByte()
    }

    private fun Request.fallbackCoverRequest(): Request? = runCatching {
        val requestUrl = url.toString()
        val mangaUrl = coverFallbackMangaUrls[requestUrl]
            ?: coverFallbackMangaUrls[requestUrl.removeSuffix(THUMBNAIL_SUFFIX)]
            ?: return null
        val detailsUrl = activeBaseUrl + mangaUrl
        val detailsDocument = client.newCall(GET(detailsUrl, requestHeaders)).execute().use { it.asGbkJsoup() }
        val chapterSelector = when (activeParseMode) {
            ParseMode.DESKTOP -> "#play_0 a[href$=.html]"
            ParseMode.MOBILE -> "#chapterList a[href$=.html]"
        }
        val chapterUrl = detailsDocument.selectFirst(chapterSelector)?.absUrl("href") ?: return null
        val chapterHtml = client.newCall(GET(chapterUrl, requestHeaders)).execute().use { it.asGbkString() }
        val coverUrl = when (activeParseMode) {
            ParseMode.DESKTOP -> parseDesktopImages(chapterHtml)
            ParseMode.MOBILE -> parseMobileImages(chapterHtml)
        }.firstOrNull() ?: return null

        val fallbackHeaders = headersBuilder()
            .set("Referer", chapterUrl)
            .set("Accept", IMAGE_ACCEPT)
            .build()
        GET(coverUrl, fallbackHeaders)
    }.getOrNull()

    private fun parseDesktopImages(html: String): List<String> {
        val chapterId = CURRENT_CHAPTER_ID_REGEX.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val picTree = PIC_TREE_REGEX.find(html)?.groupValues?.get(1).orEmpty()
        if (picTree.isBlank()) {
            return emptyList()
        }
        val decoded = String(Base64.decode(picTree, Base64.DEFAULT), Charsets.UTF_8)
        return decoded.split(PIC_TREE_SEPARATOR)
            .filter(String::isNotBlank)
            .map { it.toImageUrl(chapterId, ParseMode.DESKTOP) }
    }

    private fun parseMobileImages(html: String): List<String> {
        val mhInfo = html.extractAssignedObject("mhInfo") ?: return emptyList()
        val chapterId = mhInfo.findNumberValue("chapterId") ?: 0L
        val path = mhInfo.findStringValue("path").orEmpty()
        val images = mhInfo.findArrayValue("images")?.parseJsStringArray().orEmpty()

        return images.map { image ->
            "$path$image".toImageUrl(chapterId, ParseMode.MOBILE)
        }
    }

    private fun String.toImageUrl(chapterId: Long, mode: ParseMode): String {
        var imagePath = trim()
        IMAGE_HOST_REPLACEMENTS.forEach { (old, new) ->
            imagePath = imagePath.replace(old, new)
        }
        return when {
            imagePath.startsWith("//") -> "https:${imagePath.encodeAbsoluteUrl()}"
            imagePath.startsWith("http://") || imagePath.startsWith("https://") -> imagePath.encodeAbsoluteUrl()
            else -> imageHostFor(chapterId, mode) + imagePath.encodePath()
        }
    }

    private fun imageHostFor(chapterId: Long, mode: ParseMode): String {
        if (chapterId <= NEW_IMAGE_HOST_THRESHOLD) {
            return LEGACY_IMAGE_HOST
        }
        return when (mode) {
            ParseMode.DESKTOP -> DESKTOP_IMAGE_HOSTS.first()
            ParseMode.MOBILE -> MOBILE_IMAGE_HOSTS.first()
        }
    }

    private fun Response.asGbkJsoup(): Document {
        val html = asGbkString()
        return Jsoup.parse(html, request.url.toString())
    }

    private fun Response.asGbkString(): String = body.bytes().toString(gb18030Charset())

    private fun Document.toDesktopDetails(mangaUrl: String): SManga = SManga.create().apply {
        title = selectMeta("og:title") ?: selectFirst(".intro_l h1")?.text().orEmpty()
        thumbnail_url = selectMeta("og:image")?.removeSuffix(THUMBNAIL_SUFFIX).asCoverFor(mangaUrl)
        author = selectMeta("og:novel:author") ?: infoText("原著作者")
        genre = selectMeta("og:novel:category") ?: infoText("剧情类别")
        status = parseStatus(selectMeta("og:novel:status") ?: infoText("漫画状态").orEmpty())
        description = selectFirst("#intro1, .introduction")?.text()
            ?: selectMeta("og:description")
    }

    private fun Document.toMobileDetails(mangaUrl: String): SManga = SManga.create().apply {
        title = selectMeta("og:title") ?: selectFirst(".main-bar h1")?.text().orEmpty()
        thumbnail_url = (
            selectMeta("og:image")?.removeSuffix(THUMBNAIL_SUFFIX)
                ?: selectFirst(".book-detail .thumb img")?.imgAttr()
            ).asCoverFor(mangaUrl)
        author = selectMeta("og:novel:author") ?: detailValue("作者")
        genre = selectMeta("og:novel:category") ?: detailValue("类别")
        status = parseStatus(selectMeta("og:novel:status") ?: selectFirst(".book-detail .thumb i")?.text().orEmpty())
        description = selectFirst("#bookIntro")?.text()
            ?: selectMeta("og:description")
    }

    private fun Document.selectMeta(property: String): String? = selectFirst("meta[property=$property]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)

    private fun Document.infoText(label: String): String? = selectFirst(".intro_l .info p:contains($label)")?.ownText()?.substringAfter("：")?.trim()?.takeIf(String::isNotBlank)

    private fun Document.detailValue(label: String): String? = select(".book-detail dl").firstOrNull { it.selectFirst("dt")?.text()?.contains(label) == true }
        ?.selectFirst("dd")
        ?.text()
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun Element.toDesktopSearchManga(): SManga? {
        val titleLink = selectFirst("dl dt a[href^=/kanmanhua/]")
        val coverLink = selectFirst("p.cover a[href^=/kanmanhua/]")
        val link = titleLink ?: coverLink ?: return null
        val coverImage = selectFirst("p.cover img")
        val mangaUrl = link.absUrl("href")
        return SManga.create().apply {
            setUrlWithoutDomain(mangaUrl)
            title = titleLink?.attr("title")
                ?.ifBlank { titleLink.text() }
                ?.takeIf(String::isNotBlank)
                ?: coverImage?.attr("alt")?.takeIf(String::isNotBlank)
                ?: coverImage?.attr("title")?.takeIf(String::isNotBlank)
                ?: link.attr("title").ifBlank { link.text() }
            thumbnail_url = coverImage?.imgAttr().asCoverFor(mangaUrl)
            status = parseStatus(selectFirst("p:contains(状　态) span, p:contains(状态) span")?.text().orEmpty())
        }
    }

    private fun Element.toDesktopUpdateManga(): SManga? {
        val link = selectFirst("a.video[href^=/kanmanhua/]") ?: return null
        val mangaUrl = link.absUrl("href")
        return SManga.create().apply {
            setUrlWithoutDomain(mangaUrl)
            title = link.attr("title").ifBlank { link.text() }
            thumbnail_url = link.attr("i").takeIf(String::isNotBlank).asCoverFor(mangaUrl)
        }
    }

    private fun Element.toDesktopRankManga(): SManga = SManga.create().apply {
        val mangaUrl = absUrl("href")
        setUrlWithoutDomain(mangaUrl)
        title = attr("title").ifBlank { text() }
        thumbnail_url = attr("i").takeIf(String::isNotBlank).asCoverFor(mangaUrl)
    }

    private fun Element.toMobileListManga(): SManga = SManga.create().apply {
        val mangaUrl = absUrl("href")
        setUrlWithoutDomain(mangaUrl)
        title = selectFirst("h3")?.text() ?: attr("title").ifBlank { text() }
        thumbnail_url = selectFirst(".thumb img")?.imgAttr().asCoverFor(mangaUrl)
        status = parseStatus(selectFirst(".thumb i")?.text().orEmpty())
    }

    private fun Element.imgAttr(): String? = when {
        hasAttr("data-src") -> absUrl("data-src")
        hasAttr("data-original") -> absUrl("data-original")
        else -> absUrl("src")
    }.takeIf(String::isNotBlank)

    private fun String?.asCoverFor(mangaUrl: String): String? {
        val originalCoverUrl = this?.takeIf(String::isNotBlank) ?: return null
        val coverUrl = originalCoverUrl.cacheBustedCoverUrl()
        val relativeMangaUrl = mangaUrl.toRelativeMangaUrl()
        coverFallbackMangaUrls[originalCoverUrl] = relativeMangaUrl
        coverFallbackMangaUrls[originalCoverUrl.removeSuffix(THUMBNAIL_SUFFIX)] = relativeMangaUrl
        coverFallbackMangaUrls[coverUrl] = relativeMangaUrl
        coverFallbackMangaUrls[coverUrl.removeSuffix(THUMBNAIL_SUFFIX)] = relativeMangaUrl
        return coverUrl
    }

    private fun String.cacheBustedCoverUrl(): String {
        if (!startsWith(MIYEYE_IMAGE_BASE) || contains(COVER_CACHE_BUST_QUERY)) {
            return this
        }

        return runCatching {
            toHttpUrl()
                .newBuilder()
                .setQueryParameter(COVER_CACHE_BUST_QUERY, COVER_CACHE_BUST_VALUE)
                .build()
                .toString()
        }.getOrDefault(this)
    }

    private fun String.toRelativeMangaUrl(): String = when {
        startsWith(DESKTOP_BASE_URL) -> removePrefix(DESKTOP_BASE_URL)
        startsWith(MOBILE_BASE_URL) -> removePrefix(MOBILE_BASE_URL)
        startsWith("/") -> this
        else -> runCatching { toHttpUrl().encodedPath }.getOrDefault(this)
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("连载") -> SManga.ONGOING
        status.contains("完结") || status.contains("完成") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun String.extractAssignedObject(variableName: String): String? {
        val nameIndex = indexOf(variableName)
        if (nameIndex == -1) return null
        val equalIndex = indexOf('=', nameIndex + variableName.length)
        if (equalIndex == -1) return null
        val objectStart = indexOf('{', equalIndex)
        if (objectStart == -1) return null
        val objectEnd = findMatchingBracket(objectStart, '{', '}') ?: return null
        return substring(objectStart, objectEnd + 1)
    }

    private fun String.findNumberValue(key: String): Long? {
        val start = propertyValueStart(key) ?: return null
        val end = (start until length).firstOrNull { !this[it].isDigit() } ?: length
        return substring(start, end).toLongOrNull()
    }

    private fun String.findStringValue(key: String): String? {
        val start = propertyValueStart(key) ?: return null
        val quote = this[start]
        if (quote != '"' && quote != '\'') {
            val end = (start until length).firstOrNull { this[it] == ',' || this[it] == '}' } ?: length
            return substring(start, end).trim().takeIf(String::isNotBlank)
        }
        val end = findStringEnd(start, quote) ?: return null
        return substring(start + 1, end).unescapeJsString()
    }

    private fun String.findArrayValue(key: String): String? {
        val start = propertyValueStart(key) ?: return null
        if (this[start] != '[') return null
        val end = findMatchingBracket(start, '[', ']') ?: return null
        return substring(start + 1, end)
    }

    private fun String.propertyValueStart(key: String): Int? {
        val token = listOf("\"$key\"", "'$key'", key)
            .mapNotNull { candidate ->
                val index = indexOf(candidate)
                if (index == -1) null else index to candidate.length
            }
            .minByOrNull { it.first }
            ?: return null
        val colonIndex = indexOf(':', token.first + token.second)
        if (colonIndex == -1) return null
        return (colonIndex + 1 until length).firstOrNull { !this[it].isWhitespace() }
    }

    private fun String.findMatchingBracket(startIndex: Int, open: Char, close: Char): Int? {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in startIndex until length) {
            val char = this[index]
            when {
                escaped -> escaped = false
                quote != null && char == '\\' -> escaped = true
                quote != null && char == quote -> quote = null
                quote != null -> Unit
                char == '"' || char == '\'' -> quote = char
                char == open -> depth++
                char == close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun String.findStringEnd(startIndex: Int, quote: Char): Int? {
        var escaped = false
        for (index in startIndex + 1 until length) {
            val char = this[index]
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == quote -> return index
            }
        }
        return null
    }

    private fun String.parseJsStringArray(): List<String> {
        val items = mutableListOf<String>()
        var index = 0
        while (index < length) {
            val quote = this[index]
            if (quote != '"' && quote != '\'') {
                index++
                continue
            }
            val end = findStringEnd(index, quote) ?: break
            items += substring(index + 1, end).unescapeJsString()
            index = end + 1
        }
        return items
    }

    private fun String.encodePath(): String {
        val suffix = substringAfter("?", missingDelimiterValue = "")
        val path = substringBefore("?")
        val encodedPath = path.split("/")
            .joinToString("/") { segment ->
                if (segment.isEmpty()) {
                    segment
                } else {
                    URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
                }
            }
        return if (suffix.isEmpty()) encodedPath else "$encodedPath?$suffix"
    }

    private fun String.encodeAbsoluteUrl(): String {
        val scheme = substringBefore("://", missingDelimiterValue = "")
        if (scheme.isEmpty()) {
            return encodePath()
        }
        val rest = substringAfter("://")
        val host = rest.substringBefore("/")
        val path = rest.substringAfter("/", missingDelimiterValue = "")
        return if (path.isEmpty()) {
            "$scheme://$host"
        } else {
            "$scheme://$host/${path.encodePath()}"
        }
    }

    private fun String.unescapeJsString(): String {
        val builder = StringBuilder(length)
        var index = 0
        while (index < length) {
            val char = this[index]
            if (char != '\\' || index + 1 >= length) {
                builder.append(char)
                index++
                continue
            }

            val next = this[index + 1]
            when (next) {
                '/', '\\', '"', '\'' -> {
                    builder.append(next)
                    index += 2
                }
                'n' -> {
                    builder.append('\n')
                    index += 2
                }
                'r' -> {
                    builder.append('\r')
                    index += 2
                }
                't' -> {
                    builder.append('\t')
                    index += 2
                }
                'u' -> {
                    val code = substring(index + 2, (index + 6).coerceAtMost(length)).takeIf { it.length == 4 }
                        ?.toIntOrNull(16)
                    if (code == null) {
                        builder.append(next)
                        index += 2
                    } else {
                        builder.append(code.toChar())
                        index += 6
                    }
                }
                else -> {
                    builder.append(next)
                    index += 2
                }
            }
        }
        return builder.toString()
    }

    private enum class ParseMode(val preferenceValue: String) {
        DESKTOP("desktop"),
        MOBILE("mobile"),
        ;

        companion object {
            fun fromPreference(value: String?): ParseMode = values().firstOrNull { it.preferenceValue == value } ?: DESKTOP
        }
    }

    companion object {
        private const val DESKTOP_BASE_URL = "https://www.laimanhua88.com"
        private const val MOBILE_BASE_URL = "https://m.laimanhua88.com"
        private const val MIYEYE_IMAGE_BASE = "https://p.miyeye.cn/"
        private const val COVER_CACHE_BUST_QUERY = "laimanhua_cover_v"
        private const val COVER_CACHE_BUST_VALUE = "4"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private const val IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        private const val PIC_TREE_SEPARATOR = "\$qingtiandy\$"
        private const val NEW_IMAGE_HOST_THRESHOLD = 542724L
        private const val LEGACY_IMAGE_HOST = "https://mhpic6.tgmhfc.uk"
        private const val GB18030_NAME = "GB18030"
        private const val GBK_NAME = "GBK"
        private const val PARSE_MODE_PREF = "parseMode"
        private const val THUMBNAIL_SUFFIX = "@!180x240"

        private val PIC_TREE_REGEX = Regex("""picTree\s*=\s*'([^']+)'""")
        private val CURRENT_CHAPTER_ID_REGEX = Regex("""currentChapterid\s*=\s*['"](\d+)['"]""")

        private val DESKTOP_IMAGE_HOSTS = listOf(
            "https://mhpic7899-5.tgmhfc.uk",
            "https://mhpic5eer.tgmhfc.uk",
            "https://mhpic7ffr.tgmhfc.uk",
            "https://mhpicwwt.tgmhfc.uk",
            "https://mhpicwwx.tgmhfc.uk",
        )

        private val MOBILE_IMAGE_HOSTS = listOf(
            "https://mhreswhm.tgmhfc.uk",
            "https://xwdf.tgmhfc.uk",
            "https://qwe123.tgmhfc.uk",
            "https://resmhpic.tgmhfc.uk",
            "https://reszxc.tgmhfc.uk",
        )

        private val IMAGE_HOST_REPLACEMENTS = mapOf(
            "img1.fshmy.com" to "img1.hgysxz.cn",
            "imgs.k6188.com" to "imgs.zhujios.com",
            "073.k6188.com" to "cartoon.zhujios.com",
            "cartoon.jide123.cc" to "cartoon.shhh88.com",
            "www.jide123.com" to "cartoon.shhh88.com",
            "cartoon.chuixue123.com" to "cartoon.shhh88.com",
            "imgs.gengxin123.com" to "imgs1.ysryd.com",
            "p10.tuku.cc:8899" to "tkpic.tukucc.com",
        )

        private val IMAGE_HOST_SUFFIXES = listOf(
            ".miyeye.cn",
            ".tgmhfc.uk",
            ".hgysxz.cn",
            ".zhujios.com",
            ".shhh88.com",
            ".ysryd.com",
            ".tukucc.com",
        )

        // Switch this marker to ParseMode.MOBILE.preferenceValue to change the default for fresh installs.
        private val DEFAULT_PARSE_MODE_VALUE = ParseMode.DESKTOP.preferenceValue

        private fun gb18030Charset(): Charset = runCatching { Charset.forName(GB18030_NAME) }.getOrDefault(Charsets.UTF_8)

        private fun gbkCharset(): Charset = runCatching { Charset.forName(GBK_NAME) }.getOrDefault(Charsets.UTF_8)

        private fun String.isKnownImageHost(): Boolean = IMAGE_HOST_SUFFIXES.any { endsWith(it) }

        private fun String.isLikelyImagePath(): Boolean = substringBefore('@').substringBefore('?').let {
            it.endsWith(".jpg", ignoreCase = true) ||
                it.endsWith(".jpeg", ignoreCase = true) ||
                it.endsWith(".png", ignoreCase = true) ||
                it.endsWith(".webp", ignoreCase = true) ||
                it.endsWith(".gif", ignoreCase = true)
        }
    }
}
