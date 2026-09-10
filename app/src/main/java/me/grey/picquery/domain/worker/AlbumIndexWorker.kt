package me.grey.picquery.domain.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.grey.picquery.domain.AlbumManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Robust BACKGROUND indexing worker, enqueued every time the app is opened
 * (from [AlbumManager.initAllAlbumList] / [AlbumManager.scheduleBackgroundIndexSync]).
 *
 * It keeps the index of all "searchable" albums up to date by encoding only
 * photos that have no embedding row yet. This gives two guarantees:
 *
 * 1. RESUME: if a previous indexing job was interrupted (app killed, crash,
 *    OOM, device reboot), the albums are already persisted in the DB, so this
 *    worker picks up exactly where the previous run stopped.
 * 2. INCREMENTAL: photos newly added to already-indexed albums are detected
 *    (they have no embedding) and indexed automatically on app open.
 *
 * Robustness notes:
 * - [ExistingWorkPolicy.KEEP] ensures only one sync is queued/running at a
 *   time, no matter how often the app is opened.
 * - Returning [Result.retry] on failure/incomplete runs makes WorkManager
 *   reschedule the job (with backoff), even across process death.
 */
class AlbumIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val albumManager: AlbumManager by inject()

    override suspend fun doWork(): Result {
        return try {
            Timber.tag(TAG).d("Background album index sync started (attempt $runAttemptCount)")

            val success = albumManager.syncSearchableAlbumsInBackground()

            Timber.tag(TAG).d("Background album index sync finished, success=$success")
            if (success) {
                Result.success()
            } else {
                // Incomplete (e.g. foreground job was running, or a chunk
                // failed): let WorkManager retry later. Already-encoded
                // photos are skipped on the next attempt, so retrying is
                // cheap and safe.
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Background album index sync failed")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AlbumIndexWorker"
        private const val UNIQUE_WORK_NAME = "album-index-sync"

        /**
         * Idempotent enqueue helper — safe to call on every app open.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AlbumIndexWorker>().build()
            )
        }
    }
}
