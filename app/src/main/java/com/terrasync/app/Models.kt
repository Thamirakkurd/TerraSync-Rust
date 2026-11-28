package com.terrasync.app

enum class MapStyleMode(val id: Int, val label: String) {
    VECTOR(1, "Vector Street"),
    SATELLITE(2, "Satellite Hybrid");

    companion object {
        fun fromId(id: Int): MapStyleMode = entries.firstOrNull { it.id == id } ?: VECTOR
    }
}

enum class TravelMode(
    val id: String,
    val label: String,
    val icon: String,
    val osrmPrefix: String,
    val routeColorHex: String
) {
    CAR("car", "Car", "🚗", "routed-car", "#1E88E5"),
    WALKING("walk", "Walk", "🚶", "routed-foot", "#2E8B47"),
    BICYCLE("bike", "Bicycle", "🚲", "routed-bike", "#0F766E");

    companion object {
        fun fromId(id: String?): TravelMode = entries.firstOrNull { it.id == id } ?: CAR
    }
}

data class LayerItem(
    val id: String = "",
    val name: String = "",
    val shareCode: String = "",
    val createdBy: String = "",
    val creatorDeviceId: String = "",
    val creatorUid: String = "",
    val updatedAt: Long = 0L
)

data class PinItem(
    val id: String = "",
    val layerId: String = "",
    val name: String = "",
    val description: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val status: String = "pending",
    val issueReason: String = "",
    val updatedAt: Long = 0L,
    val isEdited: Boolean = false,
    val editedBy: String = "",
    val editedAt: Long = 0L,
    val editHistory: String = "",
    val folderPath: String = ""
)

data class TrackItem(
    val id: String = "",
    val layerId: String = "",
    val name: String = "",
    val coords: String = "",
    val updatedAt: Long = 0L,
    val folderPath: String = ""
)

data class NavStep(
    val instruction: String,
    val arrow: String,
    val distanceMeters: Double,
    val lat: Double,
    val lng: Double,
    val routeDistMeters: Double = 0.0
)

data class NavRoute(
    val geometry: List<Pair<Double, Double>>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val steps: List<NavStep>,
    val mode: TravelMode = TravelMode.CAR,
    val engineLabel: String = "Valhalla"
) {
    val segmentMetrics: Pair<DoubleArray, DoubleArray> by lazy {
        val segCount = (geometry.size - 1).coerceAtLeast(0)
        val segLens = DoubleArray(segCount)
        val cumLens = DoubleArray(geometry.size)
        for (i in 0 until segCount) {
            val len = RoutingEngine.haversineMeters(
                geometry[i].second, geometry[i].first,
                geometry[i + 1].second, geometry[i + 1].first
            )
            segLens[i] = len
            cumLens[i + 1] = cumLens[i] + len
        }
        segLens to cumLens
    }
}

data class RouteProgress(
    val offRouteMeters: Double,
    val traveledMeters: Double,
    val remainingMeters: Double,
    val snappedLat: Double,
    val snappedLng: Double,
    val segmentIndex: Int,
    val remainingGeometry: List<Pair<Double, Double>>
)

data class PlaceResult(
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lng: Double
)

enum class PinStatus(
    val key: String,
    val label: String,
    val colorHex: String,
    val iconId: String
) {
    PENDING("pending", "Pending", "#E69500", "pin-pending"),
    DONE("done", "Done", "#2E8B47", "pin-done"),
    ISSUE("issue", "Issue", "#D93025", "pin-issue");

    companion object {
        fun fromRaw(raw: String?): PinStatus {
            if (raw == null || raw.isEmpty() || raw == "pending") return PENDING
            if (raw == "done") return DONE
            if (raw == "issue") return ISSUE
            val s = raw.trim().lowercase(java.util.Locale.US)
            if (s.isEmpty() || s == "pending" || s.startsWith("pending:") || s.startsWith("pending ") || s == "todo" || s == "open") {
                return PENDING
            }
            return when {
                s == "done" || s.startsWith("done:") || s.startsWith("done ") ||
                    s.contains("done") || s.contains("complete") || s == "finished" || s == "ok" -> DONE
                s == "issue" || s.startsWith("issue:") || s.startsWith("issue ") ||
                    s.contains("issue") || s.contains("fail") || s.contains("problem") || s.contains("error") -> ISSUE
                else -> PENDING
            }
        }
    }
}

fun PinItem.normalizedStatus(): PinStatus = PinStatus.fromRaw(status)

fun statusColorHex(status: String): String = PinStatus.fromRaw(status).colorHex

fun statusIconId(status: String): String = PinStatus.fromRaw(status).iconId

fun statusLabel(status: String): String = PinStatus.fromRaw(status).label

fun formatDistanceShort(meters: Double, unit: SpeedUnit = SpeedUnit.METRIC): String {
    val safeMeters = if (meters.isFinite() && meters >= 0.0) meters else 0.0
    if (unit == SpeedUnit.IMPERIAL) {
        val feet = safeMeters * 3.28084
        val miles = safeMeters / 1609.344
        return when {
            feet < 1000.0 -> "${feet.toInt()} ft"
            miles < 10.0 -> "${"%.1f".format(java.util.Locale.US, miles)} mi"
            else -> "${miles.toInt()} mi"
        }
    }
    return when {
        safeMeters < 1000.0 -> "${safeMeters.toInt()} m"
        safeMeters < 10000.0 -> "${"%.1f".format(java.util.Locale.US, safeMeters / 1000.0)} km"
        else -> "${(safeMeters / 1000.0).toInt()} km"
    }
}

fun formatEtaDuration(durationSeconds: Double): String {
    val totalMin = (durationSeconds / 60.0).toInt().coerceAtLeast(1)
    if (totalMin < 60) return "$totalMin min"
    val hours = totalMin / 60
    val mins = totalMin % 60
    return if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
}

internal object SpeedFallbackEstimator {
    data class SpeedSampleState(
        val derivedSpeedMps: Float = 0f,
        val lat: Double = Double.NaN,
        val lng: Double = Double.NaN,
        val provider: String = "",
        val elapsedMs: Long = 0L
    )

    fun update(
        prev: SpeedSampleState,
        lat: Double,
        lng: Double,
        provider: String,
        hasSpeed: Boolean,
        speedMps: Float,
        accurate: Boolean,
        elapsedMs: Long
    ): SpeedSampleState {
        if (!accurate || provider == "network") {
            return SpeedSampleState(0f, Double.NaN, Double.NaN, "", elapsedMs)
        }
        if (hasSpeed && speedMps > 0.1f) {
            return SpeedSampleState(speedMps, lat, lng, provider, elapsedMs)
        }
        if (prev.lat.isNaN() || prev.lng.isNaN()) {
            return SpeedSampleState(0f, lat, lng, provider, elapsedMs)
        }
        val dtSec = (elapsedMs - prev.elapsedMs) / 1000f
        val dist = RoutingEngine.haversineMeters(prev.lat, prev.lng, lat, lng).toFloat()
        if (prev.provider.isNotEmpty() && provider != prev.provider) {
            // Ignore cross-provider echoes (e.g., FUSED_PROVIDER mirroring GPS_PROVIDER at the same point).
            if (dist < 1.5f && dtSec < 2.2f) {
                return prev
            }
            // Never derive speed from a coordinate jump between distinct location providers; re-anchor cleanly.
            return SpeedSampleState(0f, lat, lng, provider, elapsedMs)
        }
        if (dtSec < 0.35f) return prev
        if (dtSec in 0.35f..5.0f) {
            val speed = if (dist >= 1.5f) (dist / dtSec).coerceAtMost(55f) else 0f
            return SpeedSampleState(speed, lat, lng, provider, elapsedMs)
        }
        return SpeedSampleState(0f, lat, lng, provider, elapsedMs)
    }
}

/** Thread-safe cached date formatters to avoid allocating SimpleDateFormat per pin/row/report. */
internal object TimeFormatters {
    private val canonicalHistoryFmt = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US)
    }
    private val dayMonthFmt = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("dd MMM", java.util.Locale.US)
    }
    private val hourMinFmt = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    }
    private val csvFmt = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
    }
    private val fileStampFmt = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
    }
    private val reportFmt = object : ThreadLocal<Pair<java.util.Locale, java.text.SimpleDateFormat>>() {
        override fun initialValue(): Pair<java.util.Locale, java.text.SimpleDateFormat> {
            val loc = java.util.Locale.getDefault()
            return loc to java.text.SimpleDateFormat("dd MMM yyyy • HH:mm", loc)
        }
    }

    fun canonicalHistory(ts: Long): String = canonicalHistoryFmt.get()!!.format(java.util.Date(ts))
    fun dayMonth(ts: Long): String = dayMonthFmt.get()!!.format(java.util.Date(ts))
    fun hourMinute(ts: Long): String = hourMinFmt.get()!!.format(java.util.Date(ts))
    fun csvTimestamp(ts: Long): String = csvFmt.get()!!.format(java.util.Date(ts))
    fun fileStamp(ts: Long): String = fileStampFmt.get()!!.format(java.util.Date(ts))
    fun reportTimestamp(ts: Long): String {
        val loc = java.util.Locale.getDefault()
        var pair = reportFmt.get()!!
        if (pair.first != loc) {
            pair = loc to java.text.SimpleDateFormat("dd MMM yyyy • HH:mm", loc)
            reportFmt.set(pair)
        }
        return pair.second.format(java.util.Date(ts))
    }
}
