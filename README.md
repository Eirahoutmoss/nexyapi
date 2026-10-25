# NexYapı — Binanızın sağlık karnesi, cebinizde

Nex uygulama ailesinin Android uygulaması. Her yapı için bir **Yapı Karnesi** tutar. Ölçümler bu karnede birikir ve tek bir PDF raporu olarak çıkar.

> **Temel ilke:** Uygulama ölçer, kaydeder ve değişimi gösterir. **Güvenlik hükmü vermez.**

## Modüller

| Modül | Ne yapar | Sınırı |
|---|---|---|
| **Titreşim Parmak İzi** | Telefon zemine konur, ivmeölçerle baskın titreşim frekansı bulunur. | Ölçüm kalitesi etiketlenir; güvenilmez ölçümler grafiğe girmez. |
| **Çatlak İzleyici** | Yazdırılan referans etiketiyle çatlak genişliği mm olarak ölçülür, zaman içinde izlenir. | Etiket gerçek boyutta yazdırılmalıdır. |
| **Görünmez Hareket** | Sabit telefonla çekilen videodan hareket haritası ve büyütülmüş görüntü çıkarılır. | Android 9 ve üstü. |
| **Oda Akustiği** | Tarama sesiyle yankı süresi (RT60), oda modları ve öneriler hesaplanır. | Telefon hoparlörü 125 Hz altında zayıftır; 2–3 noktada ölçmek önerilir. |
| **Matkaptan Önce** | Manyetometreyle duvardaki demir ve akım taşıyan kablo aranır; bulunan yerler fotoğrafa işaretlenir. AR modu deneyseldir. | Bakır, plastik boru ve akımsız kablo bulunamaz. |
| **Isı Kaçağı** | İsteğe bağlı ek cihaz (ör. ESP32 + MLX90640). | Henüz yok. |

## Öne çıkanlar

- **PDF rapor ve parmak izi:** Her raporun altında verilerden hesaplanmış bir SHA-256 parmak izi bulunur.
- **Rapor doğrulama:** Ayarlar > Rapor doğrulama ekranına parmak izi girilir; rapordan sonra verilerin değişip değişmediği ve ham veri dosyalarının bütünlüğü denetlenir.
- **Yedekleme:** Bütün veriler ya da tek bir yapı `.nexyapi` dosyasına alınır. İçe aktarmada her dosya doğrulanır, var olan kayıtların üzerine yazılmaz.
- **Profesyonel rapor başlığı:** Profesyonel lisansla rapora büro adı, logo ve hazırlayan bilgisi eklenir.
- **Uygulama içi güncelleme:** Ayarlar'daki düğmeyle GitHub'daki son sürüm indirilir, doğrulanır ve kurulur.
- Çevrimdışı çalışır; tema telefonun açık/koyu ayarını izler.

## Derleme

Gerekenler: Android Studio (Ladybug veya daha yeni), JDK 17+, Android SDK 35.

```powershell
.\gradlew.bat :core:test            # çekirdek testleri
.\gradlew.bat :app:assembleDebug    # deneme APK'sı (gerçek sürümle yan yana kurulur)
.\gradlew.bat :app:connectedDebugAndroidTest   # veritabanı göç testi (telefon USB ile bağlı)
```

İmzalı sürüm için proje kökünde `nexyapi-release.jks` ve `keystore.properties` bulunmalıdır. İkisi de depoya girmez. **Her sürüm aynı anahtarla imzalanmalıdır**; anahtar kaybolursa kullanıcılar güncelleme alamaz.

## Sürüm yayımlama

1. `app/build.gradle.kts` içinde `versionCode` değerini artır, `versionName` değerini değiştir.
2. Derle ve hazırla:
   ```powershell
   .\gradlew.bat clean :core:test :app:assembleRelease
   powershell -ExecutionPolicy Bypass -File tools\release\prepare-release.ps1
   ```
3. GitHub'da `v<sürüm>` etiketli bir sürüm aç ve `dist\` klasöründeki `.apk` ile `.apk.sha256` dosyalarını yükle.

Uygulama taslak ve ön sürümleri yok sayar. `.sha256` dosyası olmayan, paket adı ya da imzası uyuşmayan APK kurulmaz.

## Lisans anahtarları

```powershell
pip install cryptography
python tools\license\nexyapi_license.py issue --id L-0001 --holder "Örnek Mühendislik Ltd." --expires 2027-10-31
```

Çıktı `NXY1-...` ile başlar; müşteri bunu Ayarlar > Lisans bölümüne yapıştırır. `private_key.pem` gizlidir ve depoya girmez. Lisans süresi dolarsa uygulama Ücretsiz sürüme döner; kayıtlar silinmez.

## Proje yapısı

```
core/           Saf Kotlin: analizler, lisans, güncelleme seçimi, rapor parmak izi (bilgisayarda test edilir)
app/            Android uygulaması (Kotlin + Jetpack Compose, Room)
tools/license/  Lisans anahtarı üretme aracı (Python)
tools/release/  Sürüm hazırlama betiği (PowerShell)
```

### Modül kimlikleri (yalnızca geliştirici için)

Veritabanında her modülün değişmez bir iç kodu vardır; kullanıcıya gösterilmez.

| Modül | Kod |
|---|---|
| Titreşim Parmak İzi | `Z2` |
| Çatlak İzleyici | `V3` |
| Görünmez Hareket | `V11` |
| Matkaptan Önce | `W1` |
| Oda Akustiği | `W3` |
| Isı Kaçağı | `V12` |
