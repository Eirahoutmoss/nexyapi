package com.eirahoutmoss.nexyapi

import android.content.Context
import com.eirahoutmoss.nexyapi.data.AppDatabase
import com.eirahoutmoss.nexyapi.data.StructureRepository
import com.eirahoutmoss.nexyapi.license.LicenseManager
import com.eirahoutmoss.nexyapi.media.ImageStore
import com.eirahoutmoss.nexyapi.backup.BackupService
import com.eirahoutmoss.nexyapi.report.BrandingStore
import com.eirahoutmoss.nexyapi.report.ReportService
import com.eirahoutmoss.nexyapi.report.ReportVerifier
import com.eirahoutmoss.nexyapi.update.UpdateManager

/**
 * Elle kurulan bağımlılık kabı. Bilinçli olarak bir DI kütüphanesi kullanılmadı:
 * daha az sihir, daha az derleme sorunu, hatada okunur yığın izi.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    private val app get() = appContext

    val database: AppDatabase by lazy { AppDatabase.build(app) }
    val structures: StructureRepository by lazy { StructureRepository(database, app.filesDir) }
    val licenses: LicenseManager by lazy { LicenseManager(app) }
    val reports: ReportService by lazy { ReportService(app, structures, licenses, images, branding) }
    val updateManager: UpdateManager by lazy { UpdateManager(app) }
    val images: ImageStore by lazy { ImageStore(app) }
    val branding: BrandingStore by lazy { BrandingStore(app, images) }
    val verifier: ReportVerifier by lazy { ReportVerifier(database, structures) }
    val backup: BackupService by lazy { BackupService(app, database, structures, licenses) }
}
