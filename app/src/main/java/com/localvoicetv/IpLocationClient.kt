package com.localvoicetv

import org.json.JSONException
import java.io.IOException
import java.net.URL

data class IpLocation(val latitude: Double, val longitude: Double)

fun interface IpLocationProvider {
    fun locate(cancellation: NetworkCancellation): IpLocation
}

/** Only used when the user asks for local weather. No weather credential is sent here. */
class IpLocationClient(
    private val transport: JsonTransport = JsonHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : IpLocationProvider {
    @Volatile private var cached: Pair<Long, IpLocation>? = null

    override fun locate(cancellation: NetworkCancellation): IpLocation {
        cancellation.check()
        cached?.let { if (clock() - it.first in 0 until 30 * 60_000L) return it.second }
        try {
            val json = transport.get(URL("https://ipwho.is/?fields=success,latitude,longitude"), emptyMap(), cancellation)
            cancellation.check()
            if (!json.getBoolean("success")) throw WeatherException("IP 定位失败，请直接说城市，例如“北京天气”")
            val point = IpLocation(json.getDouble("latitude"), json.getDouble("longitude"))
            if (!point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
                !point.longitude.isFinite() || point.longitude !in -180.0..180.0) throw JSONException("coordinates")
            cached = clock() to point
            return point
        } catch (_: IOException) {
            throw WeatherException("无法连接 IP 定位服务，请直接说城市，例如“北京天气”")
        } catch (_: JSONException) {
            throw WeatherException("IP 定位未返回有效城市位置，请直接说城市名称")
        }
    }
}
