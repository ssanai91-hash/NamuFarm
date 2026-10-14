package com.example.namufarm.webrequests

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.namufarm.BuildConfig
import com.example.namufarm.data.remote.WebQuoteTransport
import com.example.namufarm.data.remote.WebQuoteValidator
import kotlinx.coroutines.CancellationException

/** Token rotation is retried when connectivity returns, without opening the inbox. */
class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val settings = WebRequestSettings(applicationContext)
        if (!settings.paired || !settings.configured || !WebRequestPush.configured) return Result.success()
        return try {
            val token = WebRequestPush.token(applicationContext) ?: return Result.retry()
            settings.saveFcmToken(token)
            val response = settings.authenticatedApi(WebQuoteTransport.api).request(settings.url, mapOf(
                "action" to "register_push", "deviceToken" to settings.deviceToken, "fcmToken" to token
            ))
            if (response.code == "UNAUTHORIZED") return Result.failure()
            WebQuoteValidator.checkResponse(response)
            if (response.registered == true) Result.success() else Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context) {
            if (!WebRequestSettings(context).configured || !WebRequestPush.configured || !WebRequestSettings(context).paired) return
            WorkManager.getInstance(context).enqueueUniqueWork("namufarm-register-push", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PushRegistrationWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}


