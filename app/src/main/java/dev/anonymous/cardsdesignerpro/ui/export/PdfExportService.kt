package dev.anonymous.cardsdesignerpro.ui.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.ui.viewer.PdfViewerActivity
import dev.anonymous.cardsdesignerpro.util.PdfExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PdfExportService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val CHANNEL_ID = "pdf_export_channel"
    private val NOTIFICATION_ID = 405

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = ExportManager.currentRequest
        if (request == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createProgressNotification(getString(R.string.notif_export_init), 0, 100),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                createProgressNotification(getString(R.string.notif_export_init), 0, 100)
            )
        }

        ExportManager.isExporting.value = true
        ExportManager.exportProgress.value = 0f

        serviceScope.launch {
            val ctx = this@PdfExportService
            val templateName = request.template.name
            var success = false


            runCatching {
                when (request.mode) {
                    ExportManager.ExportRequest.Mode.SINGLE -> {
                        ctx.contentResolver.openOutputStream(request.outputUri!!)?.use { stream ->
                            PdfExporter.export(ctx, request.template, request.parseResult, request.shortParseResult, request.settings, stream) { done, total ->
                                updateProgressNotification(templateName, done, total)
                                ExportManager.exportProgress.value = done.toFloat() / total
                            }
                        }
                        success = true
                    }
                    ExportManager.ExportRequest.Mode.DUAL -> {
                        ctx.contentResolver.openOutputStream(request.outputUri!!)?.use { stream ->
                            PdfExporter.exportDual(ctx, request.template, request.parseResult, request.shortParseResult, request.settings, stream) { done, total ->
                                updateProgressNotification(templateName, done, total)
                                ExportManager.exportProgress.value = done.toFloat() / total
                            }
                        }
                        success = true
                    }
                    ExportManager.ExportRequest.Mode.SEPARATE -> {
                        val fs = ctx.contentResolver.openOutputStream(request.frontUri!!)
                        val bs = ctx.contentResolver.openOutputStream(request.backUri!!)
                        if (fs != null && bs != null) {
                            fs.use { f ->
                                bs.use { b ->
                                    PdfExporter.exportSeparate(ctx, request.template, request.parseResult, request.shortParseResult, request.settings, f, b) { done, total ->
                                        updateProgressNotification(templateName, done, total)
                                        ExportManager.exportProgress.value = done.toFloat() / total
                                    }
                                }
                            }
                            success = true
                        }
                    }
                }
            }.onFailure { it.printStackTrace() }

            ExportManager.isExporting.value = false
            
            // Send final completion event to Activity
            val event = if (success) {
                if (request.mode == ExportManager.ExportRequest.Mode.SEPARATE)
                    ExportEvent.ExportSuccessDual(request.frontUri!!, request.backUri!!)
                else
                    ExportEvent.ExportSuccess(request.outputUri ?: request.frontUri!!)
            } else {
                ExportEvent.ExportFailed
            }
            ExportManager.exportEvent.tryEmit(event)
            
            // Show dismissible success notification
            if (success) {
                showSuccessNotification(request)
            }

            // Cleanup
            ExportManager.currentRequest = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createProgressNotification(templateName: String, done: Int, total: Int): Notification {
        // If it's the init message it won't contain a template name, otherwise format it
        val message = if (done == 0 && total == 100 && templateName.contains("…")) {
            templateName 
        } else {
            getString(R.string.notif_export_progress, templateName)
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title_app_name))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(total, done, done == 0)
            .build()
    }

    private fun updateProgressNotification(templateName: String, done: Int, total: Int) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createProgressNotification(templateName, done, total))
    }

    private fun showSuccessNotification(request: ExportManager.ExportRequest) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val isDual = request.mode == ExportManager.ExportRequest.Mode.SEPARATE

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)

        if (isDual && request.frontUri != null && request.backUri != null) {
            builder.setContentTitle(getString(R.string.notif_export_success_title))
            builder.setContentText(getString(R.string.notif_export_success_dual_desc))

            // Action: Preview Front
            val frontIntent = Intent(this, PdfViewerActivity::class.java).apply {
                putExtra(PdfViewerActivity.EXTRA_PDF_URI, request.frontUri.toString())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val frontPi = PendingIntent.getActivity(
                this, System.currentTimeMillis().toInt(), frontIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notif_action_preview_front), frontPi)

            // Action: Preview Back
            val backIntent = Intent(this, PdfViewerActivity::class.java).apply {
                putExtra(PdfViewerActivity.EXTRA_PDF_URI, request.backUri.toString())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val backPi = PendingIntent.getActivity(
                this, (System.currentTimeMillis() + 1).toInt(), backIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notif_action_preview_back), backPi)

        } else {
            builder.setContentTitle(getString(R.string.notif_export_success_title))
            builder.setContentText(getString(R.string.notif_export_success_single_desc))

            val singleUri = request.outputUri ?: request.frontUri
            val intent = Intent(this, PdfViewerActivity::class.java).apply {
                putExtra(PdfViewerActivity.EXTRA_PDF_URI, singleUri?.toString())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, System.currentTimeMillis().toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingIntent)
        }

        manager.notify(NOTIFICATION_ID + 1, builder.build())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_export_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_export_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
