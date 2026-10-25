package com.eirahoutmoss.nexyapi.core.analysis

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Oda akustiği ölçümünden sade, kurallara dayalı öneriler. Öneriler genel kabul görmüş pratiklere
 * dayanır ve "öneri" olarak sunulur; ölçülmeyen bir şey ölçülmüş gibi söylenmez.
 */
object AcousticsAdvice {

    enum class Verdict(val label: String) {
        DEAD("Çok sönük"),
        BALANCED("Dengeli"),
        SLIGHTLY_LIVE("Biraz yankılı"),
        LIVE("Yankılı"),
    }

    /** Oturma odası / çalışma odası için yaygın hedef: 0,3–0,6 sn. */
    fun verdict(rt60MidS: Double): Verdict = when {
        rt60MidS < 0.3 -> Verdict.DEAD
        rt60MidS <= 0.6 -> Verdict.BALANCED
        rt60MidS <= 0.8 -> Verdict.SLIGHTLY_LIVE
        else -> Verdict.LIVE
    }

    data class Room(val lengthM: Double, val widthM: Double, val heightM: Double)

    fun suggest(result: AcousticsResult, room: Room?, externalSpeaker: Boolean): List<String> {
        val out = ArrayList<String>()
        val mid = result.rt60MidS ?: return listOf("Yankı süresi ölçülemediği için öneri üretilemedi. Daha sessiz bir ortamda tekrarlayın.")
        when (verdict(mid)) {
            Verdict.LIVE -> out += "Oda yankılı. Halı, kalın perde, kumaş kaplı mobilya ve dolu kitaplık yankıyı belirgin şekilde azaltır. " +
                "Ses paneli kullanacaksanız önce yan duvarlardaki ve tavandaki ilk yansıma noktalarına yerleştirin."
            Verdict.SLIGHTLY_LIVE -> out += "Oda biraz yankılı. Bir halı ya da kalın perde çoğu zaman dengeyi sağlamaya yeter."
            Verdict.BALANCED -> out += "Yankı süresi oturma ve çalışma odaları için önerilen aralıkta (0,3–0,6 sn)."
            Verdict.DEAD -> out += "Oda çok sönük; ses boğuk gelebilir. Yüzeylerin bir kısmını açıkta bırakmak ya da " +
                "kitaplık gibi düzensiz (dağıtıcı) yüzeyler kullanmak dengeyi iyileştirir."
        }
        val byF = result.bands.associateBy { it.centerHz }
        val hiBand = byF[4000.0]?.takeIf { it.quality != Quality.UNRELIABLE }?.rt60S
        if (hiBand != null && hiBand < 0.6 * mid) {
            out += "Tiz bölgede sönüm fazla: ince kumaşlar ve köpükler tizleri yutup basları bırakıyor olabilir."
        }
        val low = byF[250.0]?.takeIf { it.quality != Quality.UNRELIABLE }?.rt60S
        if (low != null && low > 1.5 * mid) {
            out += "Bas bölgesinde yankı uzun. Köşelere yerleştirilen kalın emiciler (bas tuzağı) bas yankısını azaltır."
        }
        if (room != null) {
            val modes = RoomModes.axial(room.lengthM, room.widthM, room.heightM)
            RoomModes.coincidences(modes).take(3).forEach { (a, b) ->
                out += "${fmt(a.frequencyHz)} Hz civarında ${a.axis} ve ${b.axis} modları çakışıyor; bu frekansta bas belirgin şekilde birikebilir."
            }
            out += "Dinleme yerini oda uzunluğunun yaklaşık %38'ine (ön duvardan ${fmt(room.lengthM * 0.38)} m) koymak, " +
                "bas düzensizliğini çoğu odada azaltır. Hoparlörleri arka duvardan en az 50 cm ve köşelerden uzakta tutun."
        }
        if (externalSpeaker && result.bassPeaksHz.isNotEmpty()) {
            out += "Ölçümde belirgin bas tepeleri: " + result.bassPeaksHz.joinToString(", ") { "${it.roundToInt()} Hz" } +
                ". Hoparlör ya da dinleme yerini 20–40 cm oynatıp tekrar ölçmek etkisini gösterir."
        }
        return out
    }

    private fun fmt(v: Double) = String.format(Locale.forLanguageTag("tr"), "%.1f", v)
}
