package com.localvoicetv

import android.util.Log

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

class WeatherException(message: String) : Exception(message)
class AmbiguousCityException(val cities: List<WeatherCity>) : Exception("地区名称不明确，请补充省市后再查询")

data class WeatherCity(
    val id: String, val name: String, val adm1: String, val adm2: String,
    val country: String, val latitude: Double, val longitude: Double, val timeZone: String,
) {
    val label: String get() = listOf(country, adm1, adm2, name).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

enum class WeatherPeriod(val label: String, val dayOffset: Int?) {
    CURRENT("当前", null), TODAY("今天", 0), TOMORROW("明天", 1), DAY_AFTER("后天", 2);

    companion object {
        fun parse(value: String): WeatherPeriod = when (value) {
            "", "current", "现在", "当前" -> CURRENT
            "today", "今天" -> TODAY
            "tomorrow", "明天" -> TOMORROW
            "day_after", "后天" -> DAY_AFTER
            else -> throw WeatherException("目前支持当前、今天、明天和后天的天气")
        }
    }
}

data class WeatherReport(
    val title: String, val details: String, val fetchedAt: Long,
    val attributions: List<String>, val cached: Boolean,
) {
    fun displayText(): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(fetchedAt))
        return "$title\n$details\n查询时间 $time${if (cached) "（缓存）" else ""}"
    }
}

/** City lookup + v1 current/daily weather. A client belongs to one credential configuration. */
class QWeatherClient(
    private val settings: WeatherSettings,
    private val transport: JsonTransport = JsonHttpClient(openConnection = QWeatherTls::openConnection),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ipLocation: IpLocationProvider = IpLocationClient(),
) {
    private data class Cached(val body: String, val time: Long)
    private data class Response(val json: JSONObject, val time: Long, val cached: Boolean)
    private val cache = LinkedHashMap<String, Cached>()

    fun query(city: String, period: WeatherPeriod, cancellation: NetworkCancellation): WeatherReport {
        settings.validate()
        val name = city.trim().ifEmpty { settings.defaultCity.trim() }
        val useIp = name.isEmpty() || name in setOf("本地", "这里", "当地")
        if (name.length > 60 || name.contains(Regex("今天|明天|后天|昨天|前天|大后天|下周|周末|天气"))) {
            throw WeatherException("请说城市和日期，例如“北京明天天气”；目前支持今天到后天")
        }
        try {
            cancellation.check()
            val location = if (useIp) {
                val point = ipLocation.locate(cancellation)
                val coordinates = String.format(Locale.ROOT, "%.2f,%.2f", point.longitude, point.latitude)
                lookup(coordinates, cancellation, fromCoordinates = true)
            } else lookup(name, cancellation)
            cancellation.check()
            val lat = String.format(Locale.ROOT, "%.2f", location.latitude)
            val lon = String.format(Locale.ROOT, "%.2f", location.longitude)
            val endpoint = if (period == WeatherPeriod.CURRENT) "current" else "daily"
            val params = linkedMapOf("lang" to "zh", "localTime" to "true")
            if (period != WeatherPeriod.CURRENT) params["days"] = "3"
            val response = request("/weather/v1/$endpoint/$lat/$lon", params, WEATHER_TTL, cancellation)
            val root = response.json
            val metadata = root.getJSONObject("metadata")
            if (metadata.optBoolean("zeroResult", false)) throw WeatherException("该地区暂时没有天气数据")
            val sources = metadata.getJSONArray("attributions")
            val attributions = (0 until sources.length()).map { sources.getString(it) }
            val report = if (period == WeatherPeriod.CURRENT) {
                val condition = root.getJSONObject("condition").getString("text")
                val temp = quantity(root, "temperature")
                val feelsLike = optionalQuantity(root, "feelsLike")?.let { "　体感 $it" }.orEmpty()
                val humidity = if (root.isNull("humidity")) "" else {
                    val ratio = root.getDouble("humidity")
                    if (!ratio.isFinite() || ratio !in 0.0..1.0) throw JSONException("humidity")
                    "湿度 ${(ratio * 100).roundToInt()}%"
                }
                WeatherReport("${location.label} · 当前天气", "$condition　$temp$feelsLike\n$humidity",
                    response.time, attributions, response.cached)
            } else {
                val date = localDate(location.timeZone, requireNotNull(period.dayOffset))
                val days = root.getJSONArray("days")
                val day = (0 until days.length()).map { days.getJSONObject(it) }.firstOrNull {
                    // localTime=true: daytime start is on the requested local calendar day.
                    it.getJSONObject("daytime").getString("forecastStartTime").take(10) == date
                } ?: throw WeatherException("未返回$date 的预报，请稍后重试")
                val daytime = day.getJSONObject("daytime").getJSONObject("condition").getString("text")
                val nighttime = day.getJSONObject("nighttime").getJSONObject("condition").getString("text")
                WeatherReport("${location.label} · ${period.label}（$date）",
                    "白天$daytime，夜间$nighttime\n最低 ${quantity(day, "temperatureMin")}　最高 ${quantity(day, "temperatureMax")}",
                    response.time, attributions, response.cached)
            }
            cancellation.check()
            return if (useIp) report.copy(title = "${report.title}（IP 定位）") else report
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            throw WeatherException(statusMessage(e.status))
        } catch (_: SocketTimeoutException) {
            throw WeatherException("天气查询超时，请稍后重试")
        } catch (_: IOException) {
            throw WeatherException("天气查询失败，请检查网络连接或稍后重试")
        } catch (_: JSONException) {
            synchronized(cache) { cache.clear() }
            throw WeatherException("天气服务返回的数据不完整，请稍后重试")
        }
    }

    private fun lookup(name: String, cancellation: NetworkCancellation, fromCoordinates: Boolean = false): WeatherCity {
        val root = request("/geo/v2/city/lookup", mapOf("location" to name, "number" to if (fromCoordinates) "1" else "20", "lang" to "zh"),
            CITY_TTL, cancellation).json
        val locations = root.optJSONArray("location") ?: throw WeatherException("没有找到$name，请说完整城市名称")
        val cities = (0 until locations.length()).map { index ->
            val item = locations.getJSONObject(index)
            WeatherCity(item.getString("id"), item.getString("name"), item.optString("adm1", ""),
                item.optString("adm2", ""), item.optString("country", ""), item.getDouble("lat"),
                item.getDouble("lon"), item.getString("tz")).also {
                if (!it.latitude.isFinite() || it.latitude !in -90.0..90.0 ||
                    !it.longitude.isFinite() || it.longitude !in -180.0..180.0) throw JSONException("coordinates")
            }
        }.distinctBy { it.id }
        if (cities.isEmpty()) throw WeatherException("没有找到$name，请说完整城市名称")
        fun normalize(value: String) = value.replace(Regex("省|市|区|县|\\s"), "").lowercase(Locale.ROOT)
        val exact = cities.filter { city ->
            listOf(city.id, city.name, city.adm1 + city.name, city.adm2 + city.name,
                city.adm1 + city.adm2 + city.name).any { normalize(it) == normalize(name) }
        }
        if (exact.size == 1) return exact.single()
        if (cities.size == 1) return cities.single()
        throw AmbiguousCityException(exact.ifEmpty { cities })
    }

    private fun request(path: String, params: Map<String, String>, ttl: Long, cancellation: NetworkCancellation): Response {
        val query = params.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val url = URL("https://${settings.host}$path?$query")
        cancellation.check()
        val cached = synchronized(cache) { cache[url.toString()] }
        if (cached != null && clock() - cached.time in 0 until ttl) {
            Log.i("WeatherQuery", "cache hit stage=${if (path.startsWith("/geo/")) "city" else "weather"}")
            return Response(JSONObject(cached.body), cached.time, true)
        }
        val json = transport.get(url, mapOf("X-QW-Api-Key" to settings.apiKey), cancellation)
        cancellation.check()
        val code = when {
            json.has("code") -> json.getString("code").toIntOrNull() ?: 500
            json.has("error") -> json.getJSONObject("error").optInt("status", 500)
            else -> 200
        }
        if (code != 200) {
            Log.w("WeatherQuery", "API error status=$code")
            throw WeatherException(statusMessage(code))
        }
        val now = clock()
        synchronized(cache) {
            cache[url.toString()] = Cached(json.toString(), now)
            if (cache.size > 64) cache.remove(cache.keys.first())
        }
        return Response(json, now, false)
    }

    private fun localDate(zone: String, offset: Int): String {
        if (zone !in TimeZone.getAvailableIDs()) throw JSONException("Unknown time zone")
        val timeZone = TimeZone.getTimeZone(zone)
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = clock(); add(Calendar.DAY_OF_MONTH, offset) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { this.timeZone = timeZone }.format(calendar.time)
    }

    private fun quantity(root: JSONObject, key: String): String {
        val data = root.getJSONObject(key)
        val value = data.getDouble("value")
        if (!value.isFinite()) throw JSONException(key)
        return String.format(Locale.CHINA, "%.1f", value).removeSuffix(".0") + data.getString("unit")
    }

    private fun optionalQuantity(root: JSONObject, key: String): String? = if (root.isNull(key)) null else quantity(root, key)

    private fun statusMessage(code: Int): String = when (code) {
        204 -> "该地区暂时没有天气数据"
        400 -> "城市或天气参数无效，请检查城市名称"
        401 -> "天气服务暂不可用，请稍后再试"
        402, 403 -> "天气服务暂不可用，请稍后再试"
        404 -> "暂时查不到该地区的天气，请稍后再试"
        429 -> "天气查询次数已达限制，请稍后再试"
        else -> "天气服务暂时不可用（$code），请稍后再试"
    }

    companion object {
        private const val WEATHER_TTL = 5 * 60_000L
        private const val CITY_TTL = 24 * 60 * 60_000L
    }
}
