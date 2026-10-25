package com.eirahoutmoss.nexyapi.license

import android.content.Context
import com.eirahoutmoss.nexyapi.R
import com.eirahoutmoss.nexyapi.core.license.License
import com.eirahoutmoss.nexyapi.core.license.LicenseCheck
import com.eirahoutmoss.nexyapi.core.license.LicenseVerifier
import com.eirahoutmoss.nexyapi.core.model.Plan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

data class LicenseState(
    val plan: Plan,
    val license: License?,
    /** Lisans var ama kullanılamıyorsa (süresi doldu vb.) kullanıcıya gösterilecek açıklama. */
    val problem: String?,
) {
    val holderLabel: String get() = license?.takeIf { problem == null }?.holder ?: plan.displayName
}

/**
 * Lisans anahtarını saklar ve doğrular. Tamamen çevrimdışıdır; internet gerekmez.
 *
 * Not: Cihaz saatinin geri alınmasına karşı koruma bilinçli olarak YOK. Saati yanlışlıkla
 * ileri alınmış bir cihazda lisansın kalıcı olarak "süresi dolmuş" görünmesi, kurumsal
 * müşteride korsan riskinden daha büyük bir destek sorunudur.
 */
class LicenseManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("license", Context.MODE_PRIVATE)
    private val verifier = LicenseVerifier(context.getString(R.string.license_public_key))

    private val _state = MutableStateFlow(evaluate(prefs.getString(KEY_TOKEN, null)))
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    val isVerifierConfigured: Boolean get() = verifier.isConfigured

    /** Yeni anahtarı dener; yalnızca geçerliyse kaydeder. Geçersizse mevcut lisans aynen kalır. */
    fun activate(token: String): LicenseCheck {
        val clean = token.filterNot { it.isWhitespace() }
        val check = verifier.verify(clean, LocalDate.now())
        if (check is LicenseCheck.Valid) {
            prefs.edit().putString(KEY_TOKEN, clean).apply()
            _state.value = evaluate(clean)
        }
        return check
    }

    fun deactivate() {
        prefs.edit().remove(KEY_TOKEN).apply()
        _state.value = evaluate(null)
    }

    /** Uygulama ön plana geldiğinde çağrılır; bu arada gün değişmiş olabilir. */
    fun refresh() {
        _state.value = evaluate(prefs.getString(KEY_TOKEN, null))
    }

    private fun evaluate(token: String?): LicenseState {
        if (token.isNullOrEmpty()) return LicenseState(Plan.FREE, null, null)
        return when (val c = verifier.verify(token, LocalDate.now())) {
            is LicenseCheck.Valid -> LicenseState(c.license.plan, c.license, null)
            is LicenseCheck.Expired -> LicenseState(
                Plan.FREE,
                c.license,
                "Lisansın süresi ${c.license.expires} tarihinde doldu. Ücretsiz sürüm sınırları geçerli; " +
                    "kayıtlı yapılarınız ve ölçümleriniz silinmez.",
            )
            is LicenseCheck.Invalid -> LicenseState(Plan.FREE, null, c.reason)
        }
    }

    private companion object {
        const val KEY_TOKEN = "token"
    }
}
