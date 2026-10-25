package com.eirahoutmoss.nexyapi.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.eirahoutmoss.nexyapi.NexYapiApp

/** PackageInstaller oturumunun sonucunu alır. Dışarıya kapalıdır (exported=false). */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure {
                    report(context, PackageInstaller.STATUS_FAILURE, "Kurulum onay ekranı açılamadı.")
                }
            }
            return
        }
        report(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    private fun report(context: Context, status: Int, message: String?) {
        (context.applicationContext as? NexYapiApp)?.container?.updateManager?.onInstallResult(status, message)
    }

    companion object {
        const val ACTION = "com.eirahoutmoss.nexyapi.INSTALL_RESULT"
    }
}
