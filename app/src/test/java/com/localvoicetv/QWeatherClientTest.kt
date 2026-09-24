package com.localvoicetv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CancellationException

class QWeatherClientTest {
    private val settings = WeatherSettings(apiKey = "test-key-not-a-real-credential")
    private val token get() = NetworkCancellation()
    private val epoch = SimpleDateFormat("yyyy-MM-dd HH:mm Z", Locale.ROOT).parse("2026-09-22 00:30 +0800")!!.time
    private val city = """{"id":"101010100","name":"北京","adm1":"北京市","adm2":"北京","country":"中国","lat":"39.90499","lon":"116.40529","tz":"Asia/Shanghai"}"""
    private val geo get() = JSONObject("""{"code":"200","location":[$city]}""")
    private val current get() = JSONObject("""{"metadata":{"attributions":["https://example.org/source"]},"condition":{"text":"晴"},"temperature":{"value":25.3,"unit":"°C"},"feelsLike":{"value":26,"unit":"°C"},"humidity":0.5}""")
    private val noIp = IpLocationProvider { throw AssertionError("Explicit city must not call IP provider") }

    @Test fun realTimeUsesV1CoordinatesAndCredentialHeaderOnly() {
        val calls = mutableListOf<URL>()
        val client = QWeatherClient(settings, JsonTransport { url, headers, _ ->
            calls += url
            assertEquals(settings.apiKey, headers["X-QW-Api-Key"])
            assertFalse(url.toString().contains(settings.apiKey))
            if (url.path.startsWith("/geo/")) geo else current
        }, { epoch }, noIp)
        val result = client.query("北京", WeatherPeriod.CURRENT, token)
        assertEquals("/geo/v2/city/lookup", calls[0].path)
        assertTrue(calls[0].query.contains("location=%E5%8C%97%E4%BA%AC"))
        assertEquals("/weather/v1/current/39.90/116.41", calls[1].path)
        assertTrue(result.details.contains("25.3°C"))
        assertTrue(result.details.contains("50%"))
        assertEquals(listOf("https://example.org/source"), result.attributions)
    }

    @Test fun omittedCityUsesIpCoordinatesButExplicitCityBypassesIp() {
        var ipCalls = 0
        val urls = mutableListOf<URL>()
        val client = QWeatherClient(settings, JsonTransport { url, _, _ ->
            urls += url; if (url.path.startsWith("/geo/")) geo else current
        }, { epoch }, IpLocationProvider { ipCalls++; IpLocation(39.9, 116.4) })
        assertTrue(client.query("", WeatherPeriod.CURRENT, token).title.contains("IP 定位"))
        assertTrue(urls[0].query.contains("location=116.40%2C39.90"))
        assertTrue(urls[0].query.contains("number=1"))
        client.query("北京", WeatherPeriod.CURRENT, token)
        assertEquals(1, ipCalls)
    }

    @Test fun dailyForecastSelectsCalendarDateInTheCityTimeZone() {
        fun day(date: String, text: String) = """{"daytime":{"forecastStartTime":"${date}T07:00+08:00","condition":{"text":"$text"}},"nighttime":{"condition":{"text":"晴"}},"temperatureMin":{"value":20,"unit":"°C"},"temperatureMax":{"value":29,"unit":"°C"}}"""
        val daily = JSONObject("""{"metadata":{"attributions":[]},"days":[${day("2026-09-24", "后天雨")},${day("2026-09-22", "今天晴")},${day("2026-09-23", "明天阴")}]}""")
        val client = QWeatherClient(settings, JsonTransport { url, _, _ ->
            if (url.path.startsWith("/geo/")) geo else {
                assertEquals("/weather/v1/daily/39.90/116.41", url.path)
                assertTrue(url.query.contains("days=3")); assertTrue(url.query.contains("localTime=true")); daily
            }
        }, { epoch }, noIp)
        for ((period, expected) in listOf(WeatherPeriod.TODAY to "今天晴", WeatherPeriod.TOMORROW to "明天阴", WeatherPeriod.DAY_AFTER to "后天雨")) {
            assertTrue(client.query("北京", period, token).details.contains(expected))
        }
    }

    @Test fun missingForecastDayDoesNotFallBackToToday() {
        val client = QWeatherClient(settings, JsonTransport { url, _, _ ->
            if (url.path.startsWith("/geo/")) geo else JSONObject("""{"metadata":{"attributions":[]},"days":[]}""")
        }, { epoch }, noIp)
        assertTrue(assertThrows(WeatherException::class.java) { client.query("北京", WeatherPeriod.TOMORROW, token) }.message!!.contains("2026-09-23"))
    }

    @Test fun cachesExpireAndNeverServeWeatherAsFreshAfterTtl() {
        var now = epoch
        var geoCalls = 0
        var weatherCalls = 0
        val client = QWeatherClient(settings, JsonTransport { url, _, _ ->
            if (url.path.startsWith("/geo/")) { geoCalls++; geo } else { weatherCalls++; current }
        }, { now }, noIp)
        assertFalse(client.query("北京", WeatherPeriod.CURRENT, token).cached)
        assertTrue(client.query("北京", WeatherPeriod.CURRENT, token).cached)
        now += 5 * 60_000L
        assertFalse(client.query("北京", WeatherPeriod.CURRENT, token).cached)
        assertEquals(1, geoCalls); assertEquals(2, weatherCalls)
    }

    @Test fun ambiguousCityRequiresSelectionAndIdResolvesIt() {
        val locations = JSONObject("""{"code":"200","location":[{"id":"a","name":"朝阳","adm1":"北京","lat":39,"lon":116,"tz":"Asia/Shanghai"},{"id":"b","name":"朝阳","adm1":"辽宁","lat":41,"lon":120,"tz":"Asia/Shanghai"}]}""")
        val client = QWeatherClient(settings, JsonTransport { url, _, _ -> if (url.path.startsWith("/geo/")) locations else current }, { epoch }, noIp)
        assertEquals(2, assertThrows(AmbiguousCityException::class.java) { client.query("朝阳", WeatherPeriod.CURRENT, token) }.cities.size)
        assertTrue(client.query("b", WeatherPeriod.CURRENT, token).title.contains("辽宁"))
    }

    @Test fun malformedWeatherDoesNotBecomeZeroOrPoisonCache() {
        var count = 0
        val client = QWeatherClient(settings, JsonTransport { url, _, _ ->
            if (url.path.startsWith("/geo/")) geo else if (++count == 1) JSONObject("""{"metadata":{"attributions":[]},"temperature":null}""") else current
        }, { epoch }, noIp)
        assertThrows(WeatherException::class.java) { client.query("北京", WeatherPeriod.CURRENT, token) }
        assertTrue(client.query("北京", WeatherPeriod.CURRENT, token).details.contains("25.3"))
        assertEquals(2, count)
    }

    @Test fun apiErrorsAndTimeoutsHaveUsefulMessagesWithoutCredentials() {
        for ((status, text) in listOf(401 to "天气服务暂不可用", 403 to "天气服务暂不可用", 429 to "限制", 500 to "暂时不可用")) {
            val client = QWeatherClient(settings, JsonTransport { _, _, _ -> throw HttpStatusException(status) }, { epoch }, noIp)
            val message = assertThrows(WeatherException::class.java) { client.query("北京", WeatherPeriod.CURRENT, token) }.message!!
            assertTrue(message.contains(text)); assertFalse(message.contains(settings.apiKey))
            assertFalse(message.contains("API KEY"))
        }
        val client = QWeatherClient(settings, JsonTransport { _, _, _ -> throw SocketTimeoutException() }, { epoch }, noIp)
        assertTrue(assertThrows(WeatherException::class.java) { client.query("北京", WeatherPeriod.CURRENT, token) }.message!!.contains("超时"))
    }

    @Test fun unsuccessfulGeoResponseIsNotCachedOrTreatedAsSuccess() {
        var calls = 0
        val client = QWeatherClient(settings, JsonTransport { _, _, _ -> calls++; JSONObject("""{"code":"401"}""") }, { epoch }, noIp)
        repeat(2) { assertThrows(WeatherException::class.java) { client.query("北京", WeatherPeriod.CURRENT, token) } }
        assertEquals(2, calls)
    }

    @Test fun cancelledRequestsAndInvalidSettingsNeverReachTheNetwork() {
        val never = JsonTransport { _, _, _ -> throw AssertionError("No network request expected") }
        val cancelled = token.also { it.cancel() }
        assertThrows(CancellationException::class.java) { QWeatherClient(settings, never).query("北京", WeatherPeriod.CURRENT, cancelled) }
        for (key in listOf("", "Q123456789")) {
            assertThrows(IllegalArgumentException::class.java) { QWeatherClient(WeatherSettings(apiKey = key), never).query("北京", WeatherPeriod.CURRENT, token) }
        }
        for (host in listOf("evil.example", "api.qweatherapi.com.evil.example", "api.qweatherapi.com/path", "api.qweatherapi.com:80")) {
            assertThrows(IllegalArgumentException::class.java) { WeatherSettings(host, "key").validate() }
        }
    }

    @Test fun ipLookupHasNoWeatherHeadersAndCachesOnlySuccess() {
        var now = epoch
        var calls = 0
        val provider = IpLocationClient(JsonTransport { url, headers, _ ->
            calls++; assertEquals("ipwho.is", url.host); assertTrue(headers.isEmpty())
            JSONObject("""{"success":true,"latitude":39.9,"longitude":116.4}""")
        }, { now })
        repeat(2) { assertEquals(39.9, provider.locate(token).latitude, 0.01) }
        assertEquals(1, calls)
        now += 30 * 60_000L; provider.locate(token); assertEquals(2, calls)
        val failing = IpLocationClient(JsonTransport { _, _, _ -> JSONObject("""{"success":false}""") })
        assertThrows(WeatherException::class.java) { failing.locate(token) }
    }
}
