package eu.einhq.app.track

import java.time.Instant

/**
 * One position of the phone, as the app sends it: EiN's own fix, `{lat, lng, ts, alt?, speed?,
 * course?, accuracy?}` (speed in m/s, course in degrees, accuracy in metres), with what the phone
 * said of itself when it took it, `battery` in percent, `charging` and `activity`.
 */
data class Fix(
    val lat: Double,
    val lng: Double,
    /** When it was taken, in milliseconds since the epoch. */
    val time: Long,
    val alt: Double? = null,
    val speed: Double? = null,
    val course: Double? = null,
    val accuracy: Double? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val activity: String? = null,
) {
    /** The fix as a JSON object, its numbers as they are and nothing that is not known. */
    fun json(): String = buildString {
        append("{\"lat\":").append(lat).append(",\"lng\":").append(lng)
        append(",\"ts\":\"").append(Instant.ofEpochMilli(time)).append('"')
        alt?.let { append(",\"alt\":").append(it) }
        speed?.let { append(",\"speed\":").append(it) }
        course?.let { append(",\"course\":").append(it) }
        accuracy?.let { append(",\"accuracy\":").append(it) }
        battery?.let { append(",\"battery\":").append(it) }
        charging?.let { append(",\"charging\":").append(it) }
        activity?.let { append(",\"activity\":\"").append(it).append('"') }
        append('}')
    }

    /** Whether it is worth keeping: on Earth, and known to about `MAX_ACCURACY_M` or better. */
    val usable: Boolean
        get() = lat.isFinite() && lng.isFinite() && kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lng) <= 180 &&
            (accuracy == null || accuracy <= MAX_ACCURACY_M)

    companion object {
        /** A fix less sure than this, in metres, is a guess from the cell or Wi-Fi around it, and left out. */
        const val MAX_ACCURACY_M = 100.0

        /** The body that sends `fixes`, each a `json()`: a JSON list. */
        fun body(fixes: List<String>): String = fixes.joinToString(",", "[", "]")
    }
}
