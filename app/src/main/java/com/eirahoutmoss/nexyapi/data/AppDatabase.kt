package com.eirahoutmoss.nexyapi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun qualityToText(q: MeasurementQuality): String = q.name

    // Bilinmeyen değer (ör. yeni sürümden geri dönüş) çökme yerine en temkinli değere düşer.
    @TypeConverter fun textToQuality(s: String): MeasurementQuality =
        MeasurementQuality.entries.firstOrNull { it.name == s } ?: MeasurementQuality.UNRELIABLE
}

@Database(
    entities = [StructureEntity::class, MeasurementEntity::class, ReportEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun structures(): StructureDao
    abstract fun measurements(): MeasurementDao
    abstract fun reports(): ReportDao

    companion object {
        const val NAME = "nexyapi.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                // BİLİNÇLİ olarak fallbackToDestructiveMigration KULLANILMIYOR:
                // göç unutulursa uygulama veriyi silmek yerine açıkça hata verir.
                .build()
    }
}
