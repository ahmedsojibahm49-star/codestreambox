package com.kizoo.streambox.cs3

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import dalvik.system.DexClassLoader
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * EXPERIMENTAL CloudStream (.cs3) runtime. Compiled only with
 * STREAMBOX_CS3=1 (see android/app/build.gradle.kts). Written against the
 * CloudStream `library` API but NOT compiled or run by its author — treat
 * the first build as a verification step.
 *
 * Channel `streambox/cs3` (JSON strings in and out):
 *   info, load, unload, search, details, links
 *
 * Security: a .cs3 is arbitrary code that runs INSIDE this app's process
 * with this app's permissions. It is not sandboxed. Packages are SHA-256
 * checked and structure-validated by the Dart side before they get here,
 * and the file is made read-only before loading (Android 14+ requires it).
 */
class Cs3Host(
    private val context: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {

    private class Loaded(val apis: List<MainAPI>, val loader: DexClassLoader)

    private val channel = MethodChannel(messenger, "streambox/cs3")
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val loaded = ConcurrentHashMap<String, Loaded>()

    init {
        channel.setMethodCallHandler(this)
        // Some library helpers want an application context.
        try {
            val cls = Class.forName("com.lagradost.api.ContextKt")
            cls.getMethod("setContext", WeakReference::class.java)
                .invoke(null, WeakReference(context.applicationContext))
        } catch (_: Throwable) {
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        pool.execute {
            try {
                val out: String? = when (call.method) {
                    "info" -> info()
                    "load" -> load(
                        call.argument<String>("internalName")!!,
                        call.argument<String>("path")!!,
                        call.argument<String>("pluginClassName")!!,
                    )
                    "unload" -> unload(call.argument<String>("internalName")!!)
                    "search" -> search(
                        call.argument<String>("internalName")!!,
                        call.argument<String>("apiName")!!,
                        call.argument<String>("query")!!,
                    )
                    "details" -> details(
                        call.argument<String>("internalName")!!,
                        call.argument<String>("apiName")!!,
                        call.argument<String>("url")!!,
                    )
                    "links" -> links(
                        call.argument<String>("internalName")!!,
                        call.argument<String>("apiName")!!,
                        call.argument<String>("data")!!,
                    )
                    else -> {
                        main.post { result.notImplemented() }
                        return@execute
                    }
                }
                main.post { result.success(out) }
            } catch (e: HostError) {
                main.post { result.error(e.code, e.message, null) }
            } catch (e: Throwable) {
                main.post {
                    result.error(
                        "PROVIDER_FAILED",
                        e.message ?: e.javaClass.simpleName,
                        null,
                    )
                }
            }
        }
    }

    private class HostError(val code: String, message: String) : Exception(message)

    private fun info(): String =
        JSONObject()
            .put("available", true)
            .put("apiVersions", JSONArray().put(1))
            .put("runtime", "cloudstream-library")
            .toString()

    // ----------------------------------------------------------- load

    private fun load(internalName: String, path: String, className: String): String {
        unload(internalName)
        val file = File(path)
        if (!file.isFile) throw HostError("INIT_FAILED", "Package file is missing.")
        file.setReadOnly() // required for dex loading on Android 14+

        val before = APIHolder.allProviders.toList()
        val loader = DexClassLoader(
            file.absolutePath,
            context.codeCacheDir.absolutePath,
            null,
            Cs3Host::class.java.classLoader,
        )
        try {
            val cls = loader.loadClass(className)
            val plugin = cls.getDeclaredConstructor().newInstance() as Plugin
            plugin.load(context)
        } catch (e: Throwable) {
            throw HostError("INIT_FAILED", "${e.javaClass.simpleName}: ${e.message}")
        }
        val added = APIHolder.allProviders.filter { it !in before }
        if (added.isEmpty()) {
            throw HostError("INIT_FAILED", "The package registered no providers.")
        }
        loaded[internalName] = Loaded(added, loader)
        val arr = JSONArray()
        for (api in added) {
            arr.put(
                JSONObject()
                    .put("apiName", api.name)
                    .put("name", api.name)
                    .put("lang", api.lang)
                    .put("supportedTypes", JSONArray(api.supportedTypes.map { it.name }))
                    .put("hasMainPage", api.hasMainPage),
            )
        }
        return arr.toString()
    }

    private fun unload(internalName: String): String? {
        loaded.remove(internalName)?.let { l ->
            try {
                APIHolder.allProviders.removeAll(l.apis.toSet())
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun apiOf(internalName: String, apiName: String): MainAPI {
        val l = loaded[internalName] ?: throw HostError("NOT_LOADED", "Extension is not loaded.")
        return l.apis.firstOrNull { it.name == apiName }
            ?: throw HostError("NOT_LOADED", "Provider is not loaded.")
    }

    // ----------------------------------------------------- operations

    private fun search(internalName: String, apiName: String, query: String): String {
        val api = apiOf(internalName, apiName)
        val results: List<SearchResponse> = runBlocking {
            withTimeout(20_000) { api.search(query) }
        } ?: emptyList()
        val arr = JSONArray()
        for (r in results.take(60)) {
            val year = (r as? MovieSearchResponse)?.year ?: (r as? TvSeriesSearchResponse)?.year
            arr.put(
                JSONObject()
                    .put("name", r.name)
                    .put("url", r.url)
                    .put("posterUrl", r.posterUrl)
                    .put("type", r.type?.name)
                    .put("year", year),
            )
        }
        return arr.toString()
    }

    private fun details(internalName: String, apiName: String, url: String): String {
        val api = apiOf(internalName, apiName)
        val r: LoadResponse = runBlocking {
            withTimeout(30_000) { api.load(url) }
        } ?: throw HostError("PROVIDER_FAILED", "The provider returned nothing.")

        val episodes = JSONArray()
        fun addEpisode(data: String, name: String?, season: Int?, episode: Int?, poster: String?, desc: String?) {
            episodes.put(
                JSONObject()
                    .put("data", data)
                    .put("name", name)
                    .put("season", season)
                    .put("episode", episode)
                    .put("posterUrl", poster)
                    .put("description", desc),
            )
        }
        when (r) {
            is MovieLoadResponse -> addEpisode(r.dataUrl, null, null, null, null, null)
            is LiveStreamLoadResponse -> addEpisode(r.dataUrl, null, null, null, null, null)
            is TvSeriesLoadResponse ->
                r.episodes.forEach { addEpisode(it.data, it.name, it.season, it.episode, it.posterUrl, it.description) }
            is AnimeLoadResponse ->
                r.episodes.values.firstOrNull()?.forEach {
                    addEpisode(it.data, it.name, it.season, it.episode, it.posterUrl, it.description)
                }
            else -> Unit
        }
        return JSONObject()
            .put("name", r.name)
            .put("url", r.url)
            .put("type", r.type.name)
            .put("posterUrl", r.posterUrl)
            .put("backdropUrl", r.backgroundPosterUrl)
            .put("plot", r.plot)
            .put("year", r.year)
            .put("tags", JSONArray(r.tags ?: emptyList<String>()))
            .put("episodes", episodes)
            .toString()
    }

    private fun links(internalName: String, apiName: String, data: String): String {
        val api = apiOf(internalName, apiName)
        val links = ArrayList<ExtractorLink>()
        val subs = ArrayList<SubtitleFile>()
        runBlocking {
            withTimeout(40_000) {
                api.loadLinks(
                    data,
                    false,
                    { s: SubtitleFile -> synchronized(subs) { subs.add(s) } },
                    { l: ExtractorLink -> synchronized(links) { links.add(l) } },
                )
            }
        }
        val outLinks = JSONArray()
        synchronized(links) {
            for (l in links) {
                val headers = JSONObject()
                l.headers.forEach { (k, v) -> headers.put(k, v) }
                if (l.referer.isNotBlank() && !headers.has("Referer")) {
                    headers.put("Referer", l.referer)
                }
                outLinks.put(
                    JSONObject()
                        .put("url", l.url)
                        .put("source", l.source)
                        .put("name", l.name)
                        .put("quality", if (l.quality > 0) l.quality else JSONObject.NULL)
                        .put("isM3u8", l.url.contains(".m3u8"))
                        .put("headers", headers),
                )
            }
        }
        val outSubs = JSONArray()
        synchronized(subs) {
            for (s in subs) {
                outSubs.put(JSONObject().put("lang", s.lang).put("url", s.url))
            }
        }
        return JSONObject().put("links", outLinks).put("subtitles", outSubs).toString()
    }
}
