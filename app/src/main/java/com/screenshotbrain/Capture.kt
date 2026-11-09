package com.screenshotbrain

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

object Watcher {
    private fun trigger() = OneTimeWorkRequestBuilder<ScanWorker>()
        .setConstraints(
            Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(10, TimeUnit.SECONDS)
                .build()
        ).build()

    /** Called when the app opens: makes sure the watcher chain and the safety net exist. */
    fun start(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniqueWork("sb-watch", ExistingWorkPolicy.KEEP, trigger())
        wm.enqueueUniquePeriodicWork(
            "sb-safety", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SafetyWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    /** Called by the trigger worker itself to wait for the next screenshot. */
    fun chain(ctx: Context) {
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork("sb-watch", ExistingWorkPolicy.APPEND_OR_REPLACE, trigger())
    }
}

class ScanWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        Capture.run(applicationContext)
        Watcher.chain(applicationContext)
        return Result.success()
    }
}

class SafetyWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        Capture.run(applicationContext)
        Watcher.start(applicationContext)
        return Result.success()
    }
}

object Capture {
    private fun mediaPerm() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    suspend fun run(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, mediaPerm()) != PackageManager.PERMISSION_GRANTED) return
        val added = Scanner.scanScreenshots(ctx, AppDb.get(ctx).dao(), 30)
        if (added.isNotEmpty()) notify(ctx, added)
    }

    private fun notify(ctx: Context, added: List<Item>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("capture", "Auto-saved screenshots", NotificationManager.IMPORTANCE_LOW)
        )
        val first = added.first()
        val title = if (added.size == 1) "Saved to ${first.folder}" else "${added.size} screenshots sorted"
        val text = if (added.size == 1) {
            if (first.type == "Code") "Verification code saved"
            else first.detail.ifBlank { first.text.replace("\n", " ").take(60) }
        } else {
            added.groupBy { it.folder }.entries.joinToString(" · ") { "${it.key} ${it.value.size}" }
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, "capture")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(pi).build()
        val ok = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (ok) nm.notify(1, n)
    }
}

/** Re-runs the classifier on saved text once per classifier version. */
object Reclassify {
    private const val CV = 3
    suspend fun runOnce(ctx: Context, dao: ItemDao) {
        val sp = ctx.getSharedPreferences("sb", Context.MODE_PRIVATE)
        if (sp.getInt("cv", 0) >= CV) return
        for (row in dao.allItems()) {
            val (t, d) = Extractor.classify(row.text)
            dao.reclass(row.id, t, d)
        }
        sp.edit().putInt("cv", CV).apply()
    }
}
