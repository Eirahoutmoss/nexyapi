# NexYapı — Binanızın sağlık karnesi, cebinizde

Nex uygulama ailesinin Android uygulaması. Her yapı için bir **Yapı Karnesi** tutar. Ölçümler (titreşim, çatlak, hareket, duvar tarama, akustik, ısı kaçağı) bu karnede birikir ve tek bir PDF raporu olarak çıkar.

> **Temel ilke:** Uygulama ölçer, kaydeder ve değişimi gösterir. **Güvenlik hükmü vermez.** Bu ilke arayüzde, raporun kapağında ve her sayfanın altında yazılıdır.

## Durum: Aşama 4 — Z2, V3, V11, W3 ve W1

| Bileşen | Durum |
|---|---|
| Yapı Karnesi (yapı ekle, düzenle, sil, karne ekranı, isteğe bağlı fotoğraf) | ✅ |
| Yerel veritabanı (Room, çevrimdışı, göç testli) | ✅ |
| PDF rapor + rapor parmak izi (SHA-256) + kapak fotoğrafı | ✅ |
| Lisans: Ücretsiz / Profesyonel, çevrimdışı imzalı anahtar | ✅ |
| GitHub'dan düğmeyle güncelleme (indir, doğrula, kur) | ✅ |
| Hata kaydı (cihazda tutulur, kullanıcı isterse paylaşır) | ✅ |
| Tema: telefonun açık/koyu ayarını izler, konsept paleti | ✅ |
| **Z2 Titreşim Parmak İzi** | ✅ |
| **V3 Çatlak İzleyici** (yarı otomatik, referans etiketli) | ✅ |
| **V11 Görünmez Hareket** (video, hareket haritası, büyütme) | ✅ |
| **W3 Oda Akustiği** (RT60, oda modları, öneriler) | ✅ |
| **W1 Matkaptan Önce** (dedektör, fotoğrafta işaretleme, AR deneysel) | ✅ |
| V12 (isteğe bağlı ek cihaz) | ⏳ En son |

## Z2 — Titreşim Parmak İzi

Telefon zemine yatay konur ve 1, 2 ya da 5 dakika boyunca ivmeölçer kaydedilir. Analiz `core/.../analysis/VibrationAnalyzer.kt` içinde yapılır:

1. Baştan 3, sondan 2 saniye atılır (dokunma titreşimi).
2. Düzensiz zaman damgaları sabit aralığa getirilir.
3. Alçak geçiren süzgeçle ~50 Hz'e inilir. Böylece fan ya da motor titreşimi bant içine "katlanmaz"; bu durum testle doğrulanmıştır.
4. Welch spektrumu hesaplanır, x+y (yatay) eksenleri toplanır.
5. 0,5–15 Hz bandındaki en güçlü tepe parabolik aradeğerlemeyle inceltilir.

**Kalite etiketi**
- **Güvenilmez:** Sensör 60 Hz'in altında, kayıtta 250 ms'den uzun boşluk var ya da tepe gürültüden ayrışmıyor.
- **Düşük:** Tepe zayıf, ani sarsıntı algılanmış ya da yakın güçte birden fazla tepe var.

Güvenilmez ölçümler geçmiş grafiğine girmez. Ham veri `.csv.gz` olarak saklanır ve SHA-256 özeti kayda yazılır.

## V3 — Çatlak İzleyici

1. **Referans etiketini yazdır:** Uygulama bir A4 PDF üretir: 20 mm'lik siyah kareler ve 100 mm'lik kontrol cetveli. Yazdırırken "gerçek boyut" seçilmelidir.
2. Etiket çatlağın yanına yapıştırılır ve yerinde bırakılır. Fotoğraf yaklaşık 10 cm'lik bir alanı kaplayacak kadar yakından çekilir.
3. Fotoğrafta önce etiketin 4 köşesine, sonra çatlağın iki kenarına dokunulur. Noktalar büyüteçle, parmak hızının üçte biriyle sürüklenerek ince ayar yapılır.
4. Dört köşe perspektifi düzeltir (homografi). Böylece hafif eğik çekim ölçüyü bozmaz.
5. Belirsizlik (±mm) dokunma hatasından hesaplanır ve sonuçla birlikte gösterilir.

Çatlağa bir ad vermek zorunludur ("Salon kuzey duvar" gibi). Aynı adla yapılan ölçümler zaman içinde grafikte karşılaştırılır.

## V11 — Görünmez Hareket

Telefon sabitken çekilen 10–15 saniyelik video, uzun kenarı 160 piksele küçültülmüş gri karelere çevrilir. Her pikselin parlaklığı bir zaman dizisi olarak ele alınır ve bütün piksellerin spektrumu toplanarak baskın titreşim frekansı bulunur. Ardından o banttaki genlik hareket haritası olarak gösterilir; isteğe bağlı olarak 25 kat büyütülmüş görüntü de izlenebilir (Euler yönteminin sade bir hâli).

- Her kareden kare ortalaması çıkarılır. Işık titremesi ve otomatik pozlama ayrıca tespit edilip uyarılır.
- Hareket görüntünün her yerinde benzerse kamera sallanmış sayılır.
- Android 9 ve üstünü gerektirir.

## W3 — Oda Akustiği

Telefon iki kez 5 saniyelik üstel sinüs taraması (50 Hz–16 kHz) çalar ve aynı anda kayıt alır. Kayıt ters süzgeçle evriştirilerek dürtü yanıtı elde edilir, iki tarama ortalanır. Oktav bantlarında (125 Hz–4 kHz) Schroeder geriye tümlemesi ve T20 yöntemiyle RT60 hesaplanır.

- Hoparlör gecikmesi (Bluetooth) sonucu etkilemez; bu durum testle doğrulanmıştır.
- Dinamik aralık ve doğrusal uydurma kalitesi her bant için ayrı ayrı denetlenir.
- Oda boyutları girilirse eksenel oda modları ve çakışmaları hesaplanır.
- Öneriler kurallara dayalıdır ve öneri olarak sunulur.
- Yankı süresi istatistiksel bir büyüklüktür; aynı odayı 2–3 noktada ölçmek önerilir.

## W1 — Matkaptan Önce

**Dedektör:** Boş bir noktada 2 saniyelik kalibrasyon yapılır. Ardından ölçülen alan vektörü ile taban vektör arasındaki farkın büyüklüğü (µT) canlı olarak gösterilir. Eşik, gürültünün 6 katı alınır ve en az 2 µT'dir. Cisme yaklaştıkça bip sesi sıklaşır, tepe noktası geçilince uyarı verilir.

**Fotoğrafta işaretleme:** Bulunan yerler duvar fotoğrafının üzerine çizilir: boru mavi, demir turuncu, olası kablo kesikli sarı. Bu görüntü kayda ve rapora girer.

**AR (deneysel):** ARCore ile telefonun geçtiği yerler sapma düzeyine göre renkli noktalar olarak sahnede kalır. Düz, desensiz duvarlarda ARCore konum bulamayabilir ve bu durum ekranda yazılır. ARCore isteğe bağlıdır; desteklemeyen telefonlarda yalnızca bu mod kapalı olur.

**Sınırlar:** Bakır, plastik borular ve akım geçmeyen kablolar manyetik değildir; bulunamaz. Sonuç kesinlik taşımaz.

**Kamera izni:** AR modu için `CAMERA` izni bildirildiğinden Android, sistem kamerasını da ancak bu izin verildikten sonra açar. Fotoğraf ve video çekmeden önce izin istenir; galeriden seçmek izin gerektirmez.

## Yedekleme ve dışa aktarma

**Ayarlar > Yedekleme > Yedek al** bütün yapıları, ölçümleri, ham verileri, fotoğrafları ve raporları tek bir `.nexyapi` dosyasına (zip) alır. Karne ekranındaki paylaş simgesi yalnızca o yapıyı dışa aktarır; büroya ya da bir meslektaşa göndermek için kullanılabilir.

İçe aktarma kuralları:
- Paket önce geçici klasöre açılır ve her dosyanın SHA-256 özeti denetlenir. Tek bir uyuşmazlık varsa hiçbir şey eklenmez.
- Var olan kayıtların üzerine yazılmaz, hiçbir şey silinmez; yalnızca eksikler eklenir.
- Veritabanına yazma tek bir işlemde (transaction) yapılır.
- Dosya yolları uygulama klasörünün dışına çıkamaz (zip slip koruması) ve paketler için boyut sınırı vardır.
- Ücretsiz sürümün tek yapı sınırı içe aktarmada da geçerlidir.

## Rapor doğrulama

**Ayarlar > Rapor doğrulama** ekranına raporun altındaki parmak izi girilir; ilk 12 karakter yeterlidir. Uygulama raporun üretildiği andaki ölçüm kümesini bugünkü kayıtlardan yeniden hesaplar ve sonucu üç durumdan biriyle bildirir:

- **Rapor doğrulandı:** veriler birebir aynı.
- **Veriler değişmiş:** hangi alanın değiştiği ya da hangi ölçümün silindiği listelenir.
- **Kayıt bulunamadı:** rapor bu cihazda üretilmemiş. Rapor başka cihazdan geldiyse, o cihazın yedeğini içe aktarınca doğrulanabilir.

Bunlara ek olarak her ölçümün ham veri dosyasının özeti yeniden alınır. Ayrıntılı karşılaştırma için her raporun yanına kanonik metni (`.canon.txt`) yazılır. 0.5.0 öncesi raporlarda yalnızca kaydın varlığı doğrulanabilir.

## Profesyonel rapor başlığı

Profesyonel lisansla **Ayarlar > Rapor başlığı** bölümünde büro adı, logo, hazırlayan, unvan/sicil no ve iletişim bilgisi girilir. Bu bilgilerle rapor başlığında NexYapı yerine büronun logosu ve adı, kapakta da hazırlayan ve iletişim bilgisi yer alır. Alt bilgide küçük harflerle "NexYapı ile hazırlanmıştır" yazar.

## Veritabanı göçü testi

Sürüm 1'den 2'ye geçişte eski verinin korunduğunu gerçek cihazda sınamak için telefonu USB ile bağla ve şunu çalıştır:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Proje yapısı

```
core/   Saf Kotlin. Android'e bağımlı değil, bilgisayarda test edilir.
        lisans doğrulama · sürüm karşılaştırma · güncelleme seçimi · rapor parmak izi · modül tanımları
app/    Android uygulaması (Kotlin + Jetpack Compose)
        data/        Room veritabanı (yapılar, ölçümler, raporlar)
        ui/          ekranlar ve tema
        report/      PDF üretimi
        update/      GitHub güncellemesi (PackageInstaller)
        license/     lisans yönetimi
        diagnostics/ hata kaydı, cihaz bilgisi
tools/license/  Lisans anahtarı üretme aracı (Python)
tools/release/  Sürüm hazırlama betiği (PowerShell)
```

**Kararlılık için alınan kararlar**
- DI kütüphanesi yok; bağımlılıklar `AppContainer` içinde elle kurulur.
- Veritabanında `fallbackToDestructiveMigration` **kullanılmaz**. Göç unutulursa veri silinmez, açıkça hata verilir. Şema her derlemede `app/schemas/` altına yazılır; bu klasör depoya eklenmeli.
- Kimlikler UUID'dir; ileride büro senkronizasyonunda çakışma olmaz.
- Güncelleme ve rapor dosyaları önce `.part` olarak yazılır, bitince yerine taşınır. Yarım dosya kalmaz.
- Güncelleme kendi kapsamında çalışır; ekrandan çıkılsa bile yarıda takılı kalmaz.

## Derleme

Gerekenler: Android Studio (Ladybug veya daha yeni), JDK 17+, Android SDK 35.

```powershell
.\gradlew.bat :core:test            # çekirdek testleri
.\gradlew.bat :app:assembleDebug    # deneme APK'sı (paket adı .debug ile biter, gerçek sürümle yan yana kurulur)
```

### İmzalama (bir kez)

Güncellemelerin çalışması için **her sürüm aynı anahtarla imzalanmalıdır**. Anahtar kaybolursa kullanıcılar güncelleme alamaz; yedeğini güvenli bir yerde sakla.

```powershell
keytool -genkeypair -v -keystore nexyapi-release.jks -alias nexyapi -keyalg RSA -keysize 4096 -validity 10000
```

Proje köküne `keystore.properties` dosyası oluştur. Bu dosya `.gitignore` içindedir, depoya girmez:

```
storeFile=nexyapi-release.jks
storePassword=...
keyAlias=nexyapi
keyPassword=...
```

## Lisans anahtarları

```powershell
pip install cryptography
cd tools/license
python nexyapi_license.py keygen
```

Bu komut `private_key.pem` dosyasını oluşturur. Dosya **GİZLİDİR**: depoya koyma, yedeğini al. Kaybolursa yeni anahtar üretmek gerekir ve eski lisansların hepsi geçersiz olur. Komutun ekrana bastığı açık anahtarı `app/src/main/res/values/license_key.xml` dosyasına yapıştır.

Bir müşteri için lisans üretmek:

```powershell
python nexyapi_license.py issue --id L-0001 --holder "Örnek Mühendislik Ltd." --expires 2027-10-31
python nexyapi_license.py issue --id L-0002 --holder "Süresiz Büro"        # süresiz
```

Çıktı `NXY1-...` ile başlar. Müşteri bunu **Ayarlar > Lisans** bölümüne yapıştırır. Doğrulama çevrimdışı yapılır.

Lisansın süresi dolarsa uygulama Ücretsiz sürüme döner. **Kayıtlı yapılar ve ölçümler silinmez**; yalnızca yeni yapı eklenemez.

## Sürüm yayımlama (uygulama içi güncelleme için)

1. `app/build.gradle.kts` içinde `versionCode` değerini **artır** ve `versionName` değerini değiştir (ör. `0.2.0`).
2. Derle ve hazırla:
   ```powershell
   .\gradlew.bat clean :core:test :app:testReleaseUnitTest :app:assembleRelease
   powershell -ExecutionPolicy Bypass -File tools\release\prepare-release.ps1
   ```
3. GitHub'da `Eirahoutmoss/nexyapi` deposunda `v0.2.0` etiketli bir sürüm aç. `dist\` klasöründeki iki dosyayı yükle:
   - `NexYapi-0.2.0.apk`
   - `NexYapi-0.2.0.apk.sha256`

Uygulamanın kuralları:
- Taslak ve ön sürümler (`prerelease`, `v1.0.0-beta.1` gibi) yok sayılır.
- Sürümde **tek** APK olmalıdır. Yanında `.sha256` dosyası yoksa güncelleme kurulmaz.
- İndirilen APK'nın özeti, paket adı ve imzası doğrulanmadan kurulum başlamaz.
- Depo **herkese açık** olmalıdır; uygulama GitHub'a kimlik bilgisi göndermez.

Depo adı farklıysa `app/build.gradle.kts` dosyasındaki `UPDATE_REPO_OWNER` ve `UPDATE_REPO_NAME` alanlarını değiştir.

## Rapor parmak izi

Her PDF'in altında, rapordaki verilerden hesaplanmış bir SHA-256 özeti bulunur. Bu verilere yapı bilgisi, ölçümler, ölçüm cihazı, rapor saati ve lisans sahibi dahildir. Biçim sürümlüdür (`NXY-RPT-1`). Hesaplamanın kuralları `core/.../integrity/ReportFingerprint.kt` dosyasındadır.

Üretilen her raporun parmak izi uygulamanın veritabanına kaydedilir. "Bu rapor sonradan değiştirildi mi?" sorusu ileride bu kayıtla doğrulanacak. Doğrulama ekranı sonraki aşamada eklenecek.

## Sonraki aşamalar

1. ~~Z2 Titreşim ve V3 Çatlak~~ ✅
2. **W3 Akustik** ve **V11 Hareket Büyüteci**.
3. **W1 Matkaptan Önce** (AR) ve **V12 Isı Kaçağı** (ESP32 ek cihazı).

Bunlara ek olarak planlananlar:
- Yedekleme ve dışa aktarma: telefon değişiminde veri kaybı olmaması için. Otomatik Google yedeği bilinçli olarak kapalı.
- Rapor doğrulama ekranı.
- Profesyonel sürüm için büro logolu rapor.
