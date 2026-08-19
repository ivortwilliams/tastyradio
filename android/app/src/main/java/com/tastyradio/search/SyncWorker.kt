package com.tastyradio.search

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tastyradio.TastyRadioApp
import com.tastyradio.data.Settings
import java.util.concurrent.TimeUnit

/**
 * Keeps the station index current in the background, so new stations turn up without anyone
 * remembering to fetch them.
 *
 * Constraints are real constraints: unmetered network only, and only while charging. A ten-megabyte
 * pull on mobile data is rude, and staleness is not an error — the app searches fine on last week's
 * index.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TastyRadioApp ?: return Result.success()
        app.search.sync()
        return when (app.search.syncState.value) {
            is SearchRepository.SyncState.Failed -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "station-index-sync"

        fun schedule(context: Context, frequency: Settings.RefreshFrequency) {
            val manager = WorkManager.getInstance(context)
            if (frequency == Settings.RefreshFrequency.Off) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }

            val days = if (frequency == Settings.RefreshFrequency.Daily) 1L else 7L
            val request = PeriodicWorkRequestBuilder<SyncWorker>(days, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build()
                )
                .build()

            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
