package com.example.namufarm.data

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object InventoryFirstBackup {
    fun write(filesDir: File, inventory: List<FarmInventoryEntity>, pending: List<PendingSyncEntity>,
        prefix: String = "inventory_first"): File {
        require(prefix in setOf("inventory_first", "inventory_rejected"))
        val folder = File(filesDir, "sync_backup")
        check(folder.isDirectory || folder.mkdirs()) { "재고 백업 폴더를 만들 수 없습니다." }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        val file = File(folder, "${prefix}_$stamp.json")
        check(file.createNewFile()) { "같은 시각의 재고 백업이 있습니다. 잠시 후 다시 시도해주세요." }
        try {
            val json = Gson().toJson(mapOf("createdAt" to System.currentTimeMillis(), "farm_inventory" to inventory, "pending_sync" to pending))
            FileOutputStream(file).use { out -> out.write(json.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        } catch (e: Exception) { file.delete(); throw e }
        return file
    }
}
