package com.eirahoutmoss.nexyapi.diagnostics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Her ölçüme eklenen cihaz bilgisi. Mühendis, raporda ölçümün hangi cihaz ve
 * hangi sensörle yapıldığını görebilmelidir; farklı cihazların sonuçları karşılaştırılırken bu şarttır.
 */
object DeviceInfo {

    val model: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun describe(context: Context): JSONObject {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensors = JSONArray()
        if (sm != null) {
            for (type in RELEVANT_SENSORS) {
                val s = sm.getDefaultSensor(type) ?: continue
                sensors.put(
                    JSONObject()
                        .put("type", s.stringType)
                        .put("name", s.name)
                        .put("vendor", s.vendor)
                        .put("version", s.version)
                        .put("resolution", s.resolution.toDouble())
                        .put("maxRange", s.maximumRange.toDouble())
                        .put("minDelayUs", s.minDelay),
                )
            }
        }
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("sensors", sensors)
    }

    private val RELEVANT_SENSORS = intArrayOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
    )
}
