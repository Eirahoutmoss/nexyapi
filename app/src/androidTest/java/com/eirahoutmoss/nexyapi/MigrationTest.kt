package com.eirahoutmoss.nexyapi

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eirahoutmoss.nexyapi.data.AppDatabase
import com.eirahoutmoss.nexyapi.data.Migrations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gerçek bir sürüm-1 veritabanını (Aşama 1'de cihazlara kurulan şema) elle oluşturur,
 * içine veri koyar, sonra uygulamanın güncel veritabanı kodunu göçlerle açar.
 * Room açılışta şemayı varlık tanımlarıyla karşılaştırır; uyuşmazlık varsa test düşer.
 *
 * Çalıştırma (telefon USB ile bağlıyken):  .\gradlew.bat :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "migration-test.db"

    @After fun cleanup() {
        context.deleteDatabase(name)
    }

    @Test fun v1ToCurrentKeepsData() { // 1 -> 2 -> 3
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            V1_SCHEMA.forEach { db.execSQL(it) }
            db.execSQL(
                "INSERT INTO structures VALUES ('s1','Evim','İndere Mh.','3',2018,'',1000,2000)",
            )
            db.execSQL(
                "INSERT INTO measurements VALUES ('m1','s1','Z2',3000,2.8,'Hz','2,80 Hz','OK','X','{}',NULL,'abc','')",
            )
            db.execSQL("INSERT INTO reports VALUES ('r1','s1',4000,'abc','a.pdf',1,'0.1.0')")
            db.version = 1
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            runBlocking {
                val s = room.structures().get("s1")!!
                assertEquals("Evim", s.name)
                assertEquals(2018, s.buildYear)
                assertNull(s.photoFile)
                val m = room.measurements().listForStructure("s1").single()
                assertEquals(2.8, m.value!!, 0.0)
                assertNull(m.subject)
                assertNull(m.params)
                // v3: eski raporların yeni alanları boş kalır
                val r = room.reports().all().single()
                assertEquals("abc", r.fingerprint)
                assertNull(r.measurementIds)
                assertNull(r.holder)
            }
        } finally {
            room.close()
        }
    }

    private companion object {
        /** Sürüm 1 şeması (Room'un Aşama 1'de ürettiğiyle aynı). DEĞİŞTİRMEYİN. */
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `structures` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL, " +
                "`floor` TEXT NOT NULL, `build_year` INTEGER, `notes` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `measurements` (`id` TEXT NOT NULL, `structure_id` TEXT NOT NULL, " +
                "`module_code` TEXT NOT NULL, `taken_at` INTEGER NOT NULL, `value` REAL, `unit` TEXT NOT NULL, " +
                "`summary` TEXT NOT NULL, `quality` TEXT NOT NULL, `device_model` TEXT NOT NULL, `device_info` TEXT NOT NULL, " +
                "`data_file` TEXT, `data_sha256` TEXT NOT NULL, `note` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`structure_id`) REFERENCES `structures`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_measurements_structure_id` ON `measurements` (`structure_id`)",
            "CREATE INDEX IF NOT EXISTS `index_measurements_structure_id_module_code_taken_at` ON `measurements` " +
                "(`structure_id`, `module_code`, `taken_at`)",
            "CREATE TABLE IF NOT EXISTS `reports` (`id` TEXT NOT NULL, `structure_id` TEXT NOT NULL, " +
                "`generated_at` INTEGER NOT NULL, `fingerprint` TEXT NOT NULL, `file_name` TEXT NOT NULL, " +
                "`measurement_count` INTEGER NOT NULL, `app_version` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`structure_id`) REFERENCES `structures`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_reports_structure_id` ON `reports` (`structure_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_reports_fingerprint` ON `reports` (`fingerprint`)",
        )
    }
}
