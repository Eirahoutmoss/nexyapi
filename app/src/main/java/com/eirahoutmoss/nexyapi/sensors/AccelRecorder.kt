package com.eirahoutmoss.nexyapi.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.eirahoutmoss.nexyapi.core.analysis.AccelRecording
import kotlin.math.sqrt

/** İvmeölçerin ölçüme uygunluğu. */
data class AccelCapability(
    val available: Boolean,
    val name: String,
    val vendor: String,
    /** Sensörün bildirdiği en yüksek örnekleme hızı (Hz); bildirmiyorsa null. */
    val maxRateHz: Double?,
    val resolution: Float,
) {
    val sufficient: Boolean get() = available && (maxRateHz == null || maxRateHz >= 60.0)
}

/**
 * İvmeölçer kaydı. Olaylar ana iş parçacığında değil, kendi HandlerThread'inde alınır
 * (200–500 Hz'de arayüzü yormasın, arayüz takılsa bile örnek kaçmasın).
 * Veriler ilkel diziler hâlinde biriktirilir; kutulama (boxing) ve çöp toplayıcı baskısı olmaz.
 */
class AccelRecorder(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    fun capability(): AccelCapability {
        val s = sensor ?: return AccelCapability(false, "", "", null, 0f)
        return AccelCapability(
            available = true,
            name = s.name,
            vendor = s.vendor,
            maxRateHz = s.minDelay.takeIf { it > 0 }?.let { 1_000_000.0 / it },
            resolution = s.resolution,
        )
    }

    private val lock = Any()
    private var t = LongArray(0)
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var z = FloatArray(0)
    private var n = 0
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    // Canlı sarsıntı göstergesi için son ~1 sn'lik yatay ivme (ortalaması çıkarılmış RMS).
    private val ring = FloatArray(512)
    private val ringY = FloatArray(512)
    private var ringPos = 0
    private var ringCount = 0

    val isRecording: Boolean get() = listener != null

    /** Kaydı başlatır. Sensör yoksa false. */
    fun start(expectedSeconds: Int): Boolean {
        val s = sensor ?: return false
        val m = sm ?: return false
        stop()
        val cap = (expectedSeconds + 10) * 520
        synchronized(lock) {
            t = LongArray(cap); x = FloatArray(cap); y = FloatArray(cap); z = FloatArray(cap); n = 0
            ringPos = 0; ringCount = 0
        }
        val ht = HandlerThread("nexyapi-accel").apply { start() }
        thread = ht
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                synchronized(lock) {
                    if (n == t.size) grow()
                    t[n] = e.timestamp; x[n] = e.values[0]; y[n] = e.values[1]; z[n] = e.values[2]
                    n++
                    ring[ringPos] = e.values[0]; ringY[ringPos] = e.values[1]
                    ringPos = (ringPos + 1) % ring.size
                    if (ringCount < ring.size) ringCount++
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = l
        // 200 Hz (5000 µs) bilinçli seçim: analiz zaten ~50 Hz'e iner; daha yüksek hız
        // Android 12+ için ek izin ister ve izinsiz istendiğinde debug derlemede SecurityException fırlatır.
        val ok = try {
            m.registerListener(l, s, SAMPLING_PERIOD_US, Handler(ht.looper))
        } catch (e: SecurityException) {
            // Bazı üreticiler yine de reddederse daha düşük, izin gerektirmeyen hıza düş.
            runCatching { m.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME, Handler(ht.looper)) }.getOrDefault(false)
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!ok) stop()
        return ok
    }

    /** Kaydı durdurur ve verinin kopyasını döndürür (kayıt yoksa boş). */
    fun stop(): AccelRecording {
        listener?.let { sm?.unregisterListener(it) }
        listener = null
        thread?.quitSafely()
        thread = null
        synchronized(lock) {
            return AccelRecording(t.copyOf(n), x.copyOf(n), y.copyOf(n), z.copyOf(n))
        }
    }

    val sampleCount: Int get() = synchronized(lock) { n }

    /** Son örneklerdeki yatay sarsıntı (m/s², RMS). */
    fun recentLevel(): Double = synchronized(lock) {
        val c = ringCount
        if (c < 10) return 0.0
        var mx = 0.0; var my = 0.0
        for (i in 0 until c) { mx += ring[i]; my += ringY[i] }
        mx /= c; my /= c
        var ss = 0.0
        for (i in 0 until c) { val dx = ring[i] - mx; val dy = ringY[i] - my; ss += dx * dx + dy * dy }
        sqrt(ss / c)
    }

    private companion object {
        const val SAMPLING_PERIOD_US = 5_000
    }

    private fun grow() {
        val cap = t.size * 2 + 1024
        t = t.copyOf(cap); x = x.copyOf(cap); y = y.copyOf(cap); z = z.copyOf(cap)
    }
}
