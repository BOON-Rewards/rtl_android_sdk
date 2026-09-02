package com.affina.rtlsdk

import org.json.JSONArray
import org.json.JSONObject

internal data class RTLHapticPattern(
    val version: Int,
    val events: List<Event>
) {
    internal data class Event(
        val atMilliseconds: Int,
        val type: Type,
        val durationMilliseconds: Int?,
        val intensity: Double,
        val sharpness: Double
    ) {
        enum class Type(val wireName: String) {
            IMPACT("impact"),
            CONTINUOUS("continuous");

            companion object {
                fun fromWireName(value: String): Type? = entries.firstOrNull { it.wireName == value }
            }
        }
    }

    internal class ValidationException(message: String) : IllegalArgumentException(message)

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MAXIMUM_EVENT_COUNT = 100
        const val MAXIMUM_PATTERN_DURATION_MILLISECONDS = 10_000
        const val MAXIMUM_CONTINUOUS_DURATION_MILLISECONDS = 3_000
        const val DEFAULT_INTENSITY = 0.5
        const val DEFAULT_SHARPNESS = 0.5

        fun parse(payload: JSONObject?): RTLHapticPattern {
            if (payload == null) throw ValidationException("payload must be an object")

            val version = integer(payload.opt("version"))
            if (version != PROTOCOL_VERSION) {
                throw ValidationException("only haptic protocol version 1 is supported")
            }

            val rawEvents = payload.opt("events") as? JSONArray
                ?: throw ValidationException("events must be an array")
            if (rawEvents.length() > MAXIMUM_EVENT_COUNT) {
                throw ValidationException("patterns may contain at most 100 events")
            }

            val events = ArrayList<Event>(rawEvents.length())
            for (index in 0 until rawEvents.length()) {
                val rawEvent = rawEvents.opt(index) as? JSONObject
                    ?: throw ValidationException("event $index must be an object")
                val at = integer(rawEvent.opt("at"))
                    ?.takeIf { it >= 0 }
                    ?: throw ValidationException("event $index has an invalid at value")
                val typeName = rawEvent.opt("type") as? String
                    ?: throw ValidationException("event $index has an unsupported type")
                val type = Event.Type.fromWireName(typeName)
                    ?: throw ValidationException("event $index has an unsupported type")

                val duration = when (type) {
                    Event.Type.IMPACT -> null
                    Event.Type.CONTINUOUS -> {
                        val parsed = integer(rawEvent.opt("duration"))
                            ?.takeIf { it >= 0 }
                            ?: throw ValidationException("event $index has an invalid duration")
                        if (parsed > MAXIMUM_CONTINUOUS_DURATION_MILLISECONDS) {
                            throw ValidationException("event $index exceeds the continuous duration limit")
                        }
                        parsed
                    }
                }
                val intensity = normalizedValue(
                    rawEvent,
                    "intensity",
                    DEFAULT_INTENSITY,
                    index
                )
                val sharpness = normalizedValue(
                    rawEvent,
                    "sharpness",
                    DEFAULT_SHARPNESS,
                    index
                )

                val eventEnd = at.toLong() + (duration ?: 0).toLong()
                if (eventEnd > MAXIMUM_PATTERN_DURATION_MILLISECONDS) {
                    throw ValidationException("pattern exceeds the total duration limit")
                }
                events += Event(at, type, duration, intensity, sharpness)
            }

            return RTLHapticPattern(version, events.sortedBy { it.atMilliseconds })
        }

        private fun integer(value: Any?): Int? {
            val number = value as? Number ?: return null
            val doubleValue = number.toDouble()
            if (!doubleValue.isFinite() || doubleValue % 1.0 != 0.0) return null
            if (doubleValue < Int.MIN_VALUE || doubleValue > Int.MAX_VALUE) return null
            return doubleValue.toInt()
        }

        private fun normalizedValue(
            source: JSONObject,
            key: String,
            defaultValue: Double,
            eventIndex: Int
        ): Double {
            if (!source.has(key)) return defaultValue
            val number = source.opt(key) as? Number
                ?: throw ValidationException("event $eventIndex has an invalid $key")
            val value = number.toDouble()
            if (!value.isFinite() || value !in 0.0..1.0) {
                throw ValidationException("event $eventIndex has an invalid $key")
            }
            return value
        }
    }
}

internal object RTLHapticBridgeMessage {
    fun parse(message: String): RTLHapticPattern? {
        val json = try {
            JSONObject(message)
        } catch (_: Exception) {
            throw RTLHapticPattern.ValidationException("message must be valid JSON")
        }
        if (json.optString("type") != "haptic.play") return null
        return RTLHapticPattern.parse(json.opt("payload") as? JSONObject)
    }
}
