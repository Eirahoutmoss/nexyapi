package com.eirahoutmoss.nexyapi.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Veritabanı göçleri. KURAL: yayımlanmış bir göç asla değiştirilmez; yeni değişiklik
 * yeni sürüm numarası ve yeni göç demektir. Her göç androidTest altındaki
 * MigrationTest ile gerçek bir eski veritabanı üzerinde sınanır.
 */
object Migrations {

    /** 1 -> 2: yapı fotoğrafı, ölçüm konusu ve modül parametreleri. Yalnızca sütun ekler; veri silinmez. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `structures` ADD COLUMN `photo_file` TEXT")
            db.execSQL("ALTER TABLE `measurements` ADD COLUMN `subject` TEXT")
            db.execSQL("ALTER TABLE `measurements` ADD COLUMN `params` TEXT")
        }
    }

    /** 2 -> 3: rapor doğrulaması için ölçüm kümesi ve hazırlayan. Eski raporlarda boş kalır. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `reports` ADD COLUMN `measurement_ids` TEXT")
            db.execSQL("ALTER TABLE `reports` ADD COLUMN `holder` TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
