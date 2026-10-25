package com.eirahoutmoss.nexyapi.core.analysis

import kotlin.math.sqrt

/**
 * Telefonun yönünü (dönme vektörü / birim kuaterniyon) kullanarak cihaz eksenlerindeki bir vektörü
 * sabit "dünya" eksenlerine çevirir. Android'in SensorManager.getRotationMatrixFromVector formülüyle aynıdır.
 *
 * W1'de önemli: telefon eğildiğinde dünyanın manyetik alanı cihaz eksenlerinde "değişmiş" görünür ve
 * metal sanılır. Ölçümü dünya eksenlerine çevirince eğilme etkisi ortadan kalkar.
 */
object Orientation {

    /** [rv]: dönme vektörü sensörünün değerleri (x, y, z[, w]). Dönen: satır öncelikli 3x3 matris. */
    fun matrixFromRotationVector(rv: FloatArray): DoubleArray {
        val q1 = rv[0].toDouble(); val q2 = rv[1].toDouble(); val q3 = rv[2].toDouble()
        val q0 = if (rv.size >= 4) rv[3].toDouble() else {
            val t = 1 - q1 * q1 - q2 * q2 - q3 * q3
            if (t > 0) sqrt(t) else 0.0
        }
        val sq1 = 2 * q1 * q1; val sq2 = 2 * q2 * q2; val sq3 = 2 * q3 * q3
        val q1q2 = 2 * q1 * q2; val q3q0 = 2 * q3 * q0
        val q1q3 = 2 * q1 * q3; val q2q0 = 2 * q2 * q0
        val q2q3 = 2 * q2 * q3; val q1q0 = 2 * q1 * q0
        return doubleArrayOf(
            1 - sq2 - sq3, q1q2 - q3q0, q1q3 + q2q0,
            q1q2 + q3q0, 1 - sq1 - sq3, q2q3 - q1q0,
            q1q3 - q2q0, q2q3 + q1q0, 1 - sq1 - sq2,
        )
    }

    /** Cihaz eksenlerindeki [v] vektörünü dünya eksenlerine çevirir: w = R · v. */
    fun toWorld(r: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
        r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
        r[6] * v[0] + r[7] * v[1] + r[8] * v[2],
    )
}
