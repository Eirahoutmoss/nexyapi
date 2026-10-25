package com.eirahoutmoss.nexyapi.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.eirahoutmoss.nexyapi.core.analysis.Orientation
import java.util.concurrent.atomic.AtomicReference

/**
 * Manyetometre okuyucu (µT), iki düzeltmeyle:
 *
 * 1) Eğilme dengelemesi: jiroskop tabanlı yön sensörü (GAME_ROTATION_VECTOR; manyetometre kullanmaz)
 *    ile her ölçüm sabit dünya eksenlerine çevrilir. Telefon elde gezdirilirken eğilse de
 *    dünyanın alanı "değişmiş" görünmez.
 * 2) Sabit ofset: Android, telefonun kendi mıknatıslığını (sert demir ofseti) arka planda ara ara yeniden
 *    tahmin eder; bu da değerde sıçrama yapar. İşlenmemiş (UNCALIBRATED) sensör okunur ve ofset
 *    kalibrasyon anında DONDURULUR; tarama boyunca sabit kalır.
 */
class MagRecorder(context: Context) {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val uncal: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
    private val cal: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val sensor: Sensor? = uncal ?: cal
    private val rotation: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    val available: Boolean get() = sensor != null
    val sensorName: String get() = sensor?.name ?: ""
    /** Eğilme dengelemesi var mı (cihazda jiroskop tabanlı yön sensörü)? */
    val tiltCompensated: Boolean get() = rotation != null

    /** Son okunan vektör (dünya eksenlerinde, dengeleme yoksa cihaz eksenlerinde) µT. */
    val latest = AtomicReference<DoubleArray?>(null)

    private val rotMatrix = AtomicReference<DoubleArray?>(null)
    @Volatile private var frozenBias: DoubleArray? = null
    @Volatile private var currentBias = DoubleArray(3)

    private val lock = Any()
    private val recent = ArrayDeque<DoubleArray>()
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    /** Her yeni manyetik örnekte çağrılır (sensör iş parçacığında). */
    @Volatile var onSample: ((DoubleArray) -> Unit)? = null

    fun start(): Boolean {
        val s = sensor ?: return false
        val m = sm ?: return false
        if (listener != null) return true
        val ht = HandlerThread("nexyapi-mag").apply { start() }
        thread = ht
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> rotMatrix.set(Orientation.matrixFromRotationVector(e.values))
                    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                        currentBias = doubleArrayOf(e.values[3].toDouble(), e.values[4].toDouble(), e.values[5].toDouble())
                        val b = frozenBias ?: currentBias
                        emit(doubleArrayOf(e.values[0] - b[0], e.values[1] - b[1], e.values[2] - b[2]))
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> emit(doubleArrayOf(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble()))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, acc: Int) = Unit
        }
        listener = l
        val handler = Handler(ht.looper)
        val ok = try {
            val magOk = m.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME, handler)
            rotation?.let { m.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME, handler) }
            magOk
        } catch (e: SecurityException) {
            false
        }
        if (!ok) stop()
        return ok
    }

    private fun emit(device: DoubleArray) {
        val r = rotMatrix.get()
        val v = if (r != null) Orientation.toWorld(r, device) else device
        latest.set(v)
        synchronized(lock) {
            recent.addLast(v)
            while (recent.size > 200) recent.removeFirst()
        }
        onSample?.invoke(v)
    }

    /** Kalibrasyon başlarken çağrılır: telefonun ofseti bu taramanın sonuna kadar sabitlenir. */
    fun freezeBias() { frozenBias = currentBias.copyOf() }

    fun stop() {
        listener?.let { sm?.unregisterListener(it) }
        listener = null
        thread?.quitSafely()
        thread = null
    }

    fun lastSamples(count: Int): List<DoubleArray> = synchronized(lock) { recent.toList().takeLast(count) }

    fun clearSamples() = synchronized(lock) { recent.clear() }
}
