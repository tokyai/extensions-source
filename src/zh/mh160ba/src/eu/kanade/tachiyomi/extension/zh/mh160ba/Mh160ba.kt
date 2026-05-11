package eu.kanade.tachiyomi.extension.zh.mh160ba

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

class Mh160ba :
    HttpSource(),
    ConfigurableSource {

    override val name = "漫画160"

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
        val url = when (POPULAR_PARSE_MODE) {
            ParseMode.DESKTOP -> "$DESKTOP_BASE_URL/kanmanhua/zaixian_hit.html"
            ParseMode.MOBILE -> "$MOBILE_BASE_URL/kanmanhua/zaixian_hit.html"
        }
        val headers = when (POPULAR_PARSE_MODE) {
            ParseMode.DESKTOP -> requestHeaders
            ParseMode.MOBILE -> headersBuilder()
                .set("Referer", "$MOBILE_BASE_URL/")
                .build()
        }
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asUtf8Jsoup()
        val mangas = when (POPULAR_PARSE_MODE) {
            ParseMode.DESKTOP -> document.select(".mh-works-ranking-list .mh-works-ranking-ct > ol > li")
                .mapNotNull { it.toDesktopRankManga() }
                .distinctBy { it.url }
            ParseMode.MOBILE -> document.select(".UpdateList .itemBox")
                .mapNotNull { it.toMobileItemBoxManga() }
                .distinctBy { it.url }
        }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = when (activeParseMode) {
            ParseMode.DESKTOP -> "$DESKTOP_BASE_URL/kanmanhua/zaixian_recent.html"
            ParseMode.MOBILE -> "$MOBILE_BASE_URL/kanmanhua/zaixian_recent.html"
        }
        return GET(url, requestHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asUtf8Jsoup()
        val mangas = when (activeParseMode) {
            ParseMode.DESKTOP -> document.select("li:has(.mh-worksbox)")
                .mapNotNull { it.toDesktopLatestManga() }
                .distinctBy { it.url }
            ParseMode.MOBILE -> document.select(".UpdateList .itemBox")
                .mapNotNull { it.toMobileItemBoxManga() }
                .distinctBy { it.url }
        }
        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) {
            return latestUpdatesRequest(page)
        }

        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val searchHeaders = headersBuilder()
            .set("Referer", "$MOBILE_BASE_URL/")
            .build()
        return GET("$MOBILE_BASE_URL/statics/search.aspx?key=$encodedQuery", searchHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asUtf8Jsoup()
        val mangas = document.select("#listbody li")
            .mapNotNull { it.toMobileSearchManga() }
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asUtf8Jsoup()
        return when (activeParseMode) {
            ParseMode.DESKTOP -> document.toDesktopDetails(response.request.url.toString())
            ParseMode.MOBILE -> document.toMobileDetails(response.request.url.toString())
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asUtf8Jsoup()
        val selector = when (activeParseMode) {
            ParseMode.DESKTOP -> "#play_0 a[href$=.html]"
            ParseMode.MOBILE -> "#chapterList_1 a[href$=.html], #chapterList a[href$=.html]"
        }
        return document.select(selector).map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = element.attr("title")
                    .ifBlank { element.selectFirst("p")?.text().orEmpty() }
                    .ifBlank { element.text() }
                    .trim()
            }
        }.reversed()
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.asUtf8String()
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
            coverFallbackMangaUrls.containsKey(requestUrl.removeThumbnailSuffixes())
    }

    private fun Response.hasHtmlBody(): Boolean {
        val sample = peekBody(64).bytes().dropWhile { it.toInt() <= 0x20 }
        return sample.firstOrNull() == '<'.code.toByte()
    }

    private fun Request.fallbackCoverRequest(): Request? = runCatching {
        val requestUrl = url.toString()
        val mangaUrl = coverFallbackMangaUrls[requestUrl]
            ?: coverFallbackMangaUrls[requestUrl.removeThumbnailSuffixes()]
            ?: return null
        val detailsUrl = activeBaseUrl + mangaUrl
        val detailsDocument = client.newCall(GET(detailsUrl, requestHeaders)).execute().use { it.asUtf8Jsoup() }
        val chapterSelector = when (activeParseMode) {
            ParseMode.DESKTOP -> "#play_0 a[href$=.html]"
            ParseMode.MOBILE -> "#chapterList_1 a[href$=.html], #chapterList a[href$=.html]"
        }
        val chapterUrl = detailsDocument.selectFirst(chapterSelector)?.absUrl("href") ?: return null
        val chapterHtml = client.newCall(GET(chapterUrl, requestHeaders)).execute().use { it.asUtf8String() }
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
        parseQtcmsImages(html, ParseMode.DESKTOP).takeIf { it.isNotEmpty() }?.let { return it }

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
        parseQtcmsImages(html, ParseMode.MOBILE).takeIf { it.isNotEmpty() }?.let { return it }

        val mhInfo = html.extractAssignedObject("mhInfo") ?: return emptyList()
        val chapterId = mhInfo.findNumberValue("chapterId") ?: 0L
        val path = mhInfo.findStringValue("path").orEmpty()
        val images = mhInfo.findArrayValue("images")?.parseJsStringArray().orEmpty()

        return images.map { image ->
            "$path$image".toImageUrl(chapterId, ParseMode.MOBILE)
        }
    }

    private fun parseQtcmsImages(html: String, mode: ParseMode): List<String> {
        val chapterId = QTCMS_CHAPTER_ID_REGEX.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return QTCMS_IMAGE_TREE_REGEX.findAll(html)
            .flatMap { matchResult ->
                val encoded = matchResult.groupValues[1]
                if (encoded.isBlank()) {
                    emptySequence()
                } else {
                    runCatching {
                        String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                            .split(PIC_TREE_SEPARATOR)
                            .asSequence()
                    }.getOrDefault(emptySequence())
                }
            }
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { it.toImageUrl(chapterId, mode) }
            .toList()
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
        if (chapterId in 1..NEW_IMAGE_HOST_THRESHOLD) {
            return LEGACY_IMAGE_HOST
        }
        return when (mode) {
            ParseMode.DESKTOP -> DESKTOP_IMAGE_HOSTS.first()
            ParseMode.MOBILE -> MOBILE_IMAGE_HOSTS.first()
        }
    }

    private fun Response.asUtf8Jsoup(): Document {
        val html = asUtf8String()
        return Jsoup.parse(html, request.url.toString())
    }

    private fun Response.asUtf8String(): String = body.bytes().toString(utf8Charset())

    private fun Document.toDesktopDetails(mangaUrl: String): SManga = SManga.create().apply {
        title = selectMeta("og:novel:book_name")?.cleanMangaTitle()
            ?: selectFirst(".mh-date-info-name h4 a")?.text()?.takeIf(String::isNotBlank)
            ?: selectMeta("og:title")?.cleanMangaTitle().orEmpty()
        thumbnail_url = selectMeta("og:image")?.removeThumbnailSuffixes().asCoverFor(mangaUrl)
        author = selectMeta("og:novel:author") ?: infoText("作者")
        genre = selectMeta("og:novel:category")
        status = parseStatus(selectMeta("og:novel:status").orEmpty())
        description = selectFirst(".work-introd #workint, .work-introd")?.text()
            ?: selectMeta("og:description")
    }

    private fun Document.toMobileDetails(mangaUrl: String): SManga = SManga.create().apply {
        title = selectMeta("og:novel:book_name")?.cleanMangaTitle()
            ?: selectMeta("og:title")?.cleanMangaTitle()
            ?: selectFirst(".main-bar h1, .bookName")?.text().orEmpty()
        thumbnail_url = selectMeta("og:image")?.removeThumbnailSuffixes().asCoverFor(mangaUrl)
        author = selectMeta("og:novel:author")
        genre = selectMeta("og:novel:category")
        status = parseStatus(selectMeta("og:novel:status").orEmpty())
        description = selectFirst(".detailContent")?.text()
            ?: selectMeta("og:description")
    }

    private fun Document.selectMeta(property: String): String? = selectFirst("meta[property=$property]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)

    private fun Document.infoText(label: String): String? = select(".mh-date-info p, .works-info-tc").firstOrNull { it.text().contains(label) }
        ?.text()
        ?.substringAfter("：", missingDelimiterValue = "")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun Element.toDesktopRankManga(): SManga? {
        val link = selectFirst("a.works-name[href^=/kanmanhua/], a[href^=/kanmanhua/][title]") ?: return null
        val mangaUrl = link.absUrl("href")
        return SManga.create().apply {
            setUrlWithoutDomain(mangaUrl)
            title = link.attr("title").ifBlank { link.text() }
            thumbnail_url = selectFirst("img")?.imgAttr().asCoverFor(mangaUrl)
        }
    }

    private fun Element.toDesktopLatestManga(): SManga? {
        val titleLink = selectFirst(".mh-works-title h4 a[href^=/kanmanhua/]")
        val coverLink = selectFirst(".mh-nlook-w a[href^=/kanmanhua/]")
        val link = titleLink ?: coverLink ?: return null
        val mangaUrl = link.absUrl("href")
        val coverImage = selectFirst(".mh-nlook-w img")
        return SManga.create().apply {
            setUrlWithoutDomain(mangaUrl)
            title = titleLink?.attr("title")
                ?.ifBlank { titleLink.text() }
                ?.takeIf(String::isNotBlank)
                ?: coverImage?.attr("alt")?.takeIf(String::isNotBlank)
                ?: link.attr("title").ifBlank { link.text() }
            thumbnail_url = coverImage?.imgAttr().asCoverFor(mangaUrl)
            status = parseStatus(selectFirst(".mh-works-author")?.text().orEmpty())
        }
    }

    private fun Element.toMobileItemBoxManga(): SManga? {
        val titleLink = selectFirst(".itemTxt a.title[href^=/kanmanhua/], a.title[href^=/kanmanhua/]")
        val coverLink = selectFirst(".itemImg a[href^=/kanmanhua/], a[href^=/kanmanhua/]:has(img)")
        val link = titleLink ?: coverLink ?: return null
        val mangaUrl = link.absUrl("href")
        val coverImage = coverLink?.selectFirst("img") ?: selectFirst("img")
        return SManga.create().apply {
            setUrlWithoutDomain(mangaUrl)
            title = titleLink?.text()?.takeIf(String::isNotBlank)
                ?: coverImage?.attr("alt")?.removeSuffix("漫画")?.takeIf(String::isNotBlank)
                ?: link.attr("title").ifBlank { link.text() }
            thumbnail_url = coverImage?.imgAttr().asCoverFor(mangaUrl)
            status = parseStatus(selectFirst(".txtItme")?.text().orEmpty())
        }
    }

    private fun Element.toMobileSearchManga(): SManga? {
        val coverLink = selectFirst("a.ImgA[href^=/kanmanhua/], a[href^=/kanmanhua/]:has(img)") ?: return null
        val mangaUrl = coverLink.absUrl("href")
        val coverImage = coverLink.selectFirst("img")
        return SManga.create().apply {
            setUrlWithoutDomain(mangaUrl)
            title = selectFirst("a.txtA")?.text()?.takeIf(String::isNotBlank)
                ?: coverImage?.attr("alt")?.removeSuffix("漫画")?.takeIf(String::isNotBlank)
                ?: coverLink.attr("title").ifBlank { coverLink.text() }
            thumbnail_url = coverImage?.imgAttr().asCoverFor(mangaUrl)
        }
    }

    private fun Element.imgAttr(): String? = when {
        hasAttr("data-src") -> absUrl("data-src")
        hasAttr("data-original") -> absUrl("data-original")
        else -> absUrl("src")
    }.takeIf(String::isNotBlank)

    private fun String?.asCoverFor(mangaUrl: String): String? {
        val originalCoverUrl = this?.takeIf(String::isNotBlank) ?: return null
        val normalizedCoverUrl = originalCoverUrl.removeThumbnailSuffixes()
        val coverUrl = normalizedCoverUrl.cacheBustedCoverUrl()
        val relativeMangaUrl = mangaUrl.toRelativeMangaUrl()
        listOf(originalCoverUrl, normalizedCoverUrl, coverUrl).forEach { url ->
            coverFallbackMangaUrls[url] = relativeMangaUrl
            coverFallbackMangaUrls[url.removeThumbnailSuffixes()] = relativeMangaUrl
        }
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

    private fun String.cleanMangaTitle(): String = substringBefore(",")
        .removeSuffix("漫画")
        .trim()

    private fun String.removeThumbnailSuffixes(): String {
        var url = this
        while (url.endsWith(THUMBNAIL_SUFFIX)) {
            url = url.removeSuffix(THUMBNAIL_SUFFIX)
        }
        return url
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
        private const val DESKTOP_BASE_URL = "https://www.mh160ba.com"
        private const val MOBILE_BASE_URL = "https://m.mh160ba.com"
        private const val MIYEYE_IMAGE_BASE = "https://p.miyeye.cn/"
        private const val COVER_CACHE_BUST_QUERY = "mh160ba_cover_v"
        private const val COVER_CACHE_BUST_VALUE = "1"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private const val IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        private const val PIC_TREE_SEPARATOR = "\$qingtiandy\$"
        private const val NEW_IMAGE_HOST_THRESHOLD = 542724L
        private const val LEGACY_IMAGE_HOST = "https://mhpic6.tgmhfc.uk"
        private const val UTF8_NAME = "UTF-8"
        private const val PARSE_MODE_PREF = "parseMode"
        private const val THUMBNAIL_SUFFIX = "@!180x240"

        private val PIC_TREE_REGEX = Regex("""picTree\s*=\s*'([^']+)'""")
        private val CURRENT_CHAPTER_ID_REGEX = Regex("""currentChapterid\s*=\s*['"](\d+)['"]""")
        private val QTCMS_CHAPTER_ID_REGEX = Regex("""qTcms_S_p_id\s*=\s*['"](\d+)['"]""")
        private val QTCMS_IMAGE_TREE_REGEX = Regex("""qTcms_S_m_murl_e\d*\s*=\s*["']([^"']*)["']""")

        private val DESKTOP_IMAGE_HOSTS = listOf(
            "https://mhpic5er.tgmhfc.uk",
            "https://mhpic789-5.tgmhfc.uk",
            "https://mhpic7fr.tgmhfc.uk",
            "https://mhpicwt.tgmhfc.uk",
            "https://mhpicwx.tgmhfc.uk",
        )

        private val MOBILE_IMAGE_HOSTS = listOf(
            "https://xwdf.tgmhfc.uk",
            "https://mhreswhm.tgmhfc.uk",
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

        // Desktop ranking omits covers after the first item. Switch to DESKTOP only for strict desktop ranking tests.
        private val POPULAR_PARSE_MODE = ParseMode.MOBILE

        // Switch this marker to ParseMode.MOBILE.preferenceValue to change the default for fresh installs.
        private val DEFAULT_PARSE_MODE_VALUE = ParseMode.DESKTOP.preferenceValue

        private fun utf8Charset(): Charset = runCatching { Charset.forName(UTF8_NAME) }.getOrDefault(Charsets.UTF_8)

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
