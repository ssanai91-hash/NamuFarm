package com.example.namufarm.quotemode

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.namufarm.data.CustomerInquiryDatabase
import com.example.namufarm.data.repository.CustomerInquiryRepository
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Local disposal only; this worker never contacts the server or sends a message. */
class CustomerInquiryCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        CustomerInquiryRepository.purgeExpired(CustomerInquiryDatabase.get(applicationContext))
        Result.success()
    } catch (error: CancellationException) { throw error }
      catch (_: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("initial-inquiry-disposal",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CustomerInquiryCleanupWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}
