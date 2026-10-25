package com.eirahoutmoss.nexyapi.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Kimlikler UUID metnidir (ileride büro senkronizasyonunda çakışma olmasın diye).
 * Zamanlar UTC epoch milisaniyedir; ekranda yerel saate çevrilir.
 * Şema değişirse veritabanı sürümü artırılır ve Migration yazılır (app/schemas altına şema dökülür).
 */

@Entity(tableName = "structures")
data class StructureEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val floor: String,
    @ColumnInfo(name = "build_year") val buildYear: Int?,
    val notes: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Kapak fotoğrafı (uygulama klasörüne göre yol); yoksa null. Sürüm 2'de eklendi. */
    @ColumnInfo(name = "photo_file") val photoFile: String? = null,
)

/** Ölçüm kalitesi. Değer veritabanına metin olarak yazılır; adı değiştirilmemelidir. */
enum class MeasurementQuality { OK, LOW, UNRELIABLE }

@Entity(
    tableName = "measurements",
    foreignKeys = [
        ForeignKey(
            entity = StructureEntity::class,
            parentColumns = ["id"],
            childColumns = ["structure_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("structure_id"), Index(value = ["structure_id", "module_code", "taken_at"])],
)
data class MeasurementEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "structure_id") val structureId: String,
    /** ModuleType.code (Z2, V3 ...). */
    @ColumnInfo(name = "module_code") val moduleCode: String,
    @ColumnInfo(name = "taken_at") val takenAt: Long,
    /** Modülün ana değeri (ör. frekans Hz). Değer üretmeyen modüllerde null. */
    val value: Double?,
    val unit: String,
    /** Ekranda ve raporda gösterilen kısa özet. */
    val summary: String,
    val quality: MeasurementQuality,
    @ColumnInfo(name = "device_model") val deviceModel: String,
    /** Ölçümü yapan cihazın ayrıntıları (JSON). */
    @ColumnInfo(name = "device_info") val deviceInfo: String,
    /** Ham veri dosyası (uygulama klasörüne göre yol); yoksa null. */
    @ColumnInfo(name = "data_file") val dataFile: String?,
    /** Ham verinin SHA-256 özeti; ham veri yoksa özetin kendisinin özeti. */
    @ColumnInfo(name = "data_sha256") val dataSha256: String,
    val note: String,
    /**
     * Ölçümün konusu: Z2'de ölçüm yeri ("Salon, pencere önü"), V3'te çatlağın adı
     * ("Salon kuzey duvar"). Aynı konudaki ölçümler zaman içinde karşılaştırılır. Sürüm 2'de eklendi.
     */
    val subject: String? = null,
    /** Modüle özgü ayrıntılar (JSON): V3'te dokunulan noktalar, Z2'de spektrum özeti vb. Sürüm 2'de eklendi. */
    val params: String? = null,
)

/** Üretilen her raporun kaydı: parmak izi sonradan doğrulama için saklanır. */
@Entity(
    tableName = "reports",
    foreignKeys = [
        ForeignKey(
            entity = StructureEntity::class,
            parentColumns = ["id"],
            childColumns = ["structure_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("structure_id"), Index(value = ["fingerprint"], unique = true)],
)
data class ReportEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "structure_id") val structureId: String,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    val fingerprint: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "measurement_count") val measurementCount: Int,
    @ColumnInfo(name = "app_version") val appVersion: String,
    /** Rapora giren ölçümlerin kimlikleri (virgülle). Doğrulamada aynı küme yeniden hesaplanır. Sürüm 3'te eklendi. */
    @ColumnInfo(name = "measurement_ids") val measurementIds: String? = null,
    /** Raporu hazırlayan (lisans sahibi ya da "Ücretsiz"); parmak izinin parçasıdır. Sürüm 3'te eklendi. */
    val holder: String? = null,
)
