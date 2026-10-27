package com.example.namufarm.data

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Private app storage only; payload_json is kept verbatim, even if it contains invalid JSON. */
internal object PendingSyncBackup {
    private val backupName = Regex("pending_sync_\\d{8}_\\d{6}\\.json")

    fun write(filesDir: File, pending: List<PendingSyncEntity>): File {
        val directory = File(filesDir, "sync_backup")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("백업 폴더 생성 실패")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "pending_sync_$stamp.json")
        // Never overwrite an earlier backup if two requests occur in the same second.
        if (!file.createNewFile()) throw IOException("같은 시각의 백업이 이미 있습니다")
        try {
            val json = Gson().toJson(pending.map { row ->
                linkedMapOf<String, Any?>(
                    "sync_id" to row.syncId,
                    "sync_type" to row.syncType,
                    "payload_json" to row.payloadJson,
                    "created_at" to row.createdAt,
                    "retry_count" to row.retryCount,
                    "last_error" to row.lastError
                )
            })
            FileOutputStream(file).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        } catch (e: Exception) {
            file.delete() // Only this incomplete backup, never another file.
            throw e
        }
        prune(directory, file)
        return file
    }

    private fun prune(directory: File, newest: File) {
        val older = directory.listFiles { candidate ->
            candidate.isFile && candidate != newest && backupName.matches(candidate.name)
        } ?: throw IOException("백업 목록 읽기 실패")
        older.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .drop(9).forEach { old ->
                if (!old.delete()) throw IOException("오래된 백업 정리 실패")
            }
    }
}
