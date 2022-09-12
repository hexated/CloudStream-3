package com.lagradost.cloudstream3.liveproviders

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI

class TimefourTv : MainAPI() {
    override var mainUrl = "https://time4tv.stream"
    override var name = "Time4tv"
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    override val mainPage = mainPageOf(
        "$mainUrl/tv-channels" to "All Channels",
        "$mainUrl/usa-channels" to "USA Channels",
        "$mainUrl/uk-channels" to "UK Channels",
        "$mainUrl/sports-channels" to "Sport Channels",
        "$mainUrl/live-sports-streams" to "Live Sport Channels",
        "$mainUrl/news-channels" to "News Channels",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (request.name == "All Channels") {
            app.get("${request.data}$page.php").document
        } else {
            app.get("${request.data}.php").document
        }
        val home = document.select("div.tab-content ul li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): LiveSearchResponse? {
        return LiveSearchResponse(
            this.selectFirst("div.channelName")?.text() ?: return null,
            fixUrl(this.selectFirst("a")!!.attr("href")),
            this@TimefourTv.name,
            TvType.Live,
            fixUrlNull(this.selectFirst("img")?.attr("src")),
        )

    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val link = document.selectFirst("div.playit")?.attr("onclick")?.substringAfter("open('")
            ?.substringBefore("',")?.let { link ->
            app.get(link).document.selectFirst("div.tv_palyer iframe")?.attr("src")
        }
        return LiveStreamLoadResponse(
            document.selectFirst("div.channelHeading h1")?.text() ?: return null,
            url,
            this.name,
            link ?: return null,
            fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content")),
            plot = document.selectFirst("div.tvText")?.text() ?: return null
        )
    }

    private var mainServer: String? = null
    private suspend fun getLink(url: String, ref: String? = null): String? {
        val doc = app.get(fixUrl(url), allowRedirects = false).document
        val iframe = doc.selectFirst("iframe")?.attr("src")
        val channel = iframe?.split("?")?.last()

        val docSecond = app.get(fixUrl(iframe ?: return null), allowRedirects = false).text
        val iframe2 = docSecond.substringAfter("<iframe  src=\"").substringBefore("'+")

        val docThird = app.get(fixUrl("$iframe2$channel.php"), allowRedirects = false).document
        val iframe3 = docThird.selectFirst("iframe")?.attr("src") ?: return null
        mainServer = URI(iframe3).let {
            "${it.scheme}://${it.host}"
        }
        if(url.contains("abc/2.php")) {
        Log.i("hexated", fixUrl(iframe3))
        Log.i("hexated", mainServer!!)
        }

        val docFourth = app.get(fixUrl(iframe3), allowRedirects = false).document
        val scriptData = docFourth.selectFirst("div#player")?.nextElementSibling()?.data()
            ?.substringAfterLast("return(")?.substringBefore(".join")

        return scriptData?.removeSurrounding("[", "]")?.replace("\"", "")?.split(",")
            ?.joinToString("")

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        app.get(fixUrl(data), allowRedirects = false).document.let { doc ->
//            val iframe = doc.selectFirst("iframe")?.attr("src")
//            doc.select("div.stream_button a").map { item ->
//                val streamsCount = item.text().replace("Stream", "").trim()
//                val testLink = iframe?.replace(Regex("[0-9].php"), "$streamsCount.php") ?: return@let
//                Log.i("hexated", testLink)
//                testLink
//            }
            doc.select("div.stream_button a").apmap { item ->
                val link = app.get(fixUrl(item.attr("href")), allowRedirects = false).document.selectFirst("iframe")?.attr("src") ?: return@apmap
//                Log.i("hexated", fixUrl(link))
                getLink(link)?.let { m3uLink ->
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = m3uLink,
                            referer = "$mainServer/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = true,
//                                headers = mapOf("Origin" to mainServer)
                        )
                    )
                }
            }
        }

        return true
    }

}