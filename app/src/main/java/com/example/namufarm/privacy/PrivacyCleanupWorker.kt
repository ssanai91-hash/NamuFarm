package com.example.namufarm.privacy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.namufarm.data.AppDatabase
import com.example.namufarm.data.local.LocalDataSource
import java.util.concurrent.TimeUnit

class PrivacyCleanupWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {
        val db=AppDatabase.getDatabase(applicationContext)
        val local=LocalDataSource(db,db.managementDao(),SellerKeys(applicationContext))
        local.protectLegacyCustomerCopies();local.cleanupPrivacy()
        java.io.File(applicationContext.cacheDir,"quote-documents").listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>24L*60*60*1000 }?.forEach { it.deleteRecursively() }
        Result.success()
    } catch(e: kotlinx.coroutines.CancellationException) { throw e }
      catch(e: Exception) { Result.retry() } // Never log customer records or encryption material.
    companion object {
        fun schedule(context: Context) { WorkManager.getInstance(context).enqueueUniquePeriodicWork("customer-data-disposal",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PrivacyCleanupWorker>(15,TimeUnit.MINUTES).build()) }
    }
}
