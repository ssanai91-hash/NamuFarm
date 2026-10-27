package com.example.namufarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Xml
import android.widget.Toast
import androidx.room.RoomDatabase
import com.example.namufarm.BuildConfig
import com.example.namufarm.quotemode.InitialQuoteDatabase
import com.example.namufarm.data.remote.MasterDeviceCredentials
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DataMaintenance {
    val lock = Mutex()
    private val mutableActive = MutableStateFlow(false)
    val state = mutableActive.asStateFlow()
    @Volatile var active = false
        set(value) { field = value; mutableActive.value = value }
}
data class BackupDbInfo(val name: String, val userVersion: Int, val sha256: String, val rows: Map<String, Long>)
data class BackupManifest(val packageName: String, val versionName: String, val versionCode: Int,
    val createdAt: Long, val databases: List<BackupDbInfo>, val preferences: Map<String, String>)
data class BackupPreview(val directory: File, val manifest: BackupManifest)
data class BackupStatus(val busy: Boolean = false, val preview: BackupPreview? = null, val message: String? = null)

/** No tokens or keystore/key material enter the archive. Export pauses writers and then recreates the UI. */
class DataBackupController(private val context: Context, private val quiesce: suspend () -> Unit,
    private val resumeUi: () -> Unit) {
    private val gate = Mutex()
    private val mutable = MutableStateFlow(BackupStatus())
    val state = mutable.asStateFlow()
    private val prefs = listOf("quote_workflow_v1", "initial_formal_quotes_v1", "quote_issuer", "NamuFarmPrefs")
    private val maximums = mapOf("NamuFarm.db" to 14, "NamuFarmQuotes.db" to 1, "NamuFarmInquiries.db" to 1)
    private val tables = mapOf("NamuFarm.db" to listOf("farm_info", "species", "farm_inventory", "quote_list", "order_list", "pending_sync"),
        "NamuFarmQuotes.db" to listOf("web_quote_request", "deferred_web_request"),
        "NamuFarmInquiries.db" to listOf("customer_inquiry", "customer_inquiry_draft", "customer_inquiry_operation", "customer_inquiry_sync"))
    fun filename() = "NamuFarm-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.KOREA).format(Date()) + ".zip"
    private fun databases(): Map<String, RoomDatabase> = linkedMapOf("NamuFarm.db" to AppDatabase.getDatabase(context),
        "NamuFarmQuotes.db" to InitialQuoteDatabase.get(context), "NamuFarmInquiries.db" to CustomerInquiryDatabase.get(context))
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun inspect(name: String, file: File): BackupDbInfo {
        file.inputStream().use { input ->
            val header = ByteArray(16); check(input.read(header) == 16 && String(header, Charsets.US_ASCII) == "SQLite format 3\u0000") { "SQLite 파일 형식이 아닙니다: $name" }
        }
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val version = db.rawQuery("PRAGMA user_version", null).use { check(it.moveToFirst()); it.getInt(0) }
            check(version in 1..requireNotNull(maximums[name])) { "이 앱에서 복원할 수 없는 DB 버전입니다: $name" }
            db.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) { "자료 무결성 확인 실패: $name" } }
            val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            val counts = tables[name].orEmpty().filter { it in names }.associateWith { table ->
                db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { check(it.moveToFirst()); it.getLong(0) }
            }
            return BackupDbInfo(name, version, hash(file), counts)
        } finally { db.close() }
    }
    private fun preferenceXml(name: String, target: File) {
        val xml = Xml.newSerializer()
        FileOutputStream(target).use { out ->
            xml.setOutput(out, "UTF-8"); xml.startDocument("UTF-8", true); xml.startTag(null, "map")
            for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                when (value) {
                    is String -> { xml.startTag(null, "string"); xml.attribute(null, "name", key); xml.text(value); xml.endTag(null, "string") }
                    is Set<*> -> { xml.startTag(null, "set"); xml.attribute(null, "name", key); value.forEach { item ->
                        check(item is String); xml.startTag(null, "string"); xml.text(item); xml.endTag(null, "string")
                    }; xml.endTag(null, "set") }
                    else -> {
                        val type = when (value) { is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; else -> error("설정 백업 형식 오류") }
                        xml.startTag(null, type); xml.attribute(null, "name", key); xml.attribute(null, "value", value.toString()); xml.endTag(null, type)
                    }
                }
            }
            xml.endTag(null, "map"); xml.endDocument(); xml.flush(); out.fd.sync()
        }
    }
    private fun snapshot(target: File): BackupManifest {
        val work = File(context.cacheDir, "backup-stage-${UUID.randomUUID()}")
        check(work.mkdirs())
        try {
            val dbInfos = databases().map { (name, room) ->
                val db = room.openHelper.writableDatabase
                db.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0 &&
                        (cursor.getInt(1) < 0 || cursor.getInt(2) >= cursor.getInt(1))) { "자료가 저장 중입니다. 백업을 다시 시도해주세요." }
                }
                val source = File(db.path); val copied = File(work, name)
                val before = hash(source)
                source.inputStream().use { input -> FileOutputStream(copied).use { out -> input.copyTo(out); out.fd.sync() } }
                check(hash(source) == before && hash(copied) == before) { "백업 중 자료가 바뀌었습니다. 다시 시도해주세요." }
                inspect(name, copied)
            }
            val preferenceHashes = prefs.associateWith { name -> preferenceXml(name, File(work, "$name.xml")); hash(File(work, "$name.xml")) }
            val manifest = BackupManifest(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                System.currentTimeMillis(), dbInfos, preferenceHashes)
            FileOutputStream(target).use { stream ->
                ZipOutputStream(stream).use { zip ->
                    fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                    entry("manifest.json", Gson().toJson(manifest).toByteArray(Charsets.UTF_8))
                    dbInfos.forEach { info -> zip.putNextEntry(ZipEntry("databases/${info.name}")); File(work, info.name).inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                    prefs.forEach { name -> zip.putNextEntry(ZipEntry("prefs/$name.xml")); File(work, "$name.xml").inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                    zip.finish(); zip.flush(); stream.fd.sync()
                }
            }
            return manifest
        } catch (e: Exception) { target.delete(); throw e }
        finally { work.deleteRecursively() }
    }
    private fun validate(uri: Uri): BackupPreview {
        val directory = File(context.cacheDir, "restore-preview-${UUID.randomUUID()}"); check(directory.mkdirs())
        try {
            val allowed = setOf("manifest.json") + maximums.keys.map { "databases/$it" } + prefs.map { "prefs/$it.xml" }
            val seen = mutableSetOf<String>(); var total = 0L
            context.contentResolver.openInputStream(uri).use { source ->
                check(source != null) { "백업 파일을 열 수 없습니다." }
                ZipInputStream(source).use { zip -> while (true) {
                    val entry = zip.nextEntry ?: break
                    check(!entry.isDirectory && entry.name in allowed && seen.add(entry.name)) { "백업 안의 파일 이름이 올바르지 않습니다." }
                    val file = File(directory, entry.name); check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    FileOutputStream(file).use { out ->
                        val b = ByteArray(65536); var count = 0L
                        while (true) { val n = zip.read(b); if (n < 0) break; count += n; total += n
                            check(total <= 512L * 1024 * 1024 && (entry.name != "manifest.json" || count <= 1000000) &&
                                (!entry.name.startsWith("prefs/") || count <= 16L * 1024 * 1024)) { "백업 파일이 너무 큽니다." }
                            out.write(b, 0, n)
                        }; out.fd.sync()
                    }; zip.closeEntry()
                } }
            }
            val manifest = Gson().fromJson(File(directory, "manifest.json").readText(), BackupManifest::class.java)
            check(manifest.packageName == BuildConfig.APPLICATION_ID && manifest.createdAt > 0 && manifest.versionCode > 0)
            check(manifest.databases.map { it.name }.toSet() == maximums.keys && manifest.databases.size == maximums.size)
            check(manifest.preferences.keys == prefs.toSet())
            manifest.databases.forEach { expected ->
                val file = File(directory, "databases/${expected.name}"); val actual = inspect(expected.name, file)
                check(actual == expected) { "백업 DB 정보가 일치하지 않습니다: ${expected.name}" }
            }
            prefs.forEach { name ->
                val file = File(directory, "prefs/$name.xml")
                check(hash(file) == manifest.preferences[name]) { "백업 설정 정보가 일치하지 않습니다." }
                val raw = file.readText(); check(!raw.contains("<!DOCTYPE", true) && !raw.contains("<!ENTITY", true))
                val parser = Xml.newPullParser(); parser.setInput(raw.reader())
                var event = parser.eventType; var rootSeen = false
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        if (!rootSeen) { check(parser.name == "map"); rootSeen = true }
                        else check(parser.name in setOf("string", "set", "int", "long", "float", "boolean"))
                    }; event = parser.next()
                }; check(rootSeen)
            }
            return BackupPreview(directory, manifest)
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }
    suspend fun inspectArchive(uri: Uri) = gate.withLock {
        mutable.value = mutable.value.copy(busy = true, message = null)
        try { val preview = withContext(Dispatchers.IO) { validate(uri) }; mutable.value = BackupStatus(preview = preview) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = BackupStatus(message = "백업을 확인하지 못했습니다. 현재 자료는 그대로입니다: ${e.message}") }
    }
    fun dismissPreview() { mutable.value.preview?.directory?.deleteRecursively(); mutable.value = BackupStatus() }
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
    suspend fun export(uri: Uri) = withContext(NonCancellable) { gate.withLock {
        mutable.value = BackupStatus(busy = true); DataMaintenance.active = true
        var paused = false
        try {
            paused = true; quiesce()
            withContext(Dispatchers.IO) { DataMaintenance.lock.withLock {
                val target = File(context.cacheDir, "export-${UUID.randomUUID()}.zip")
                try {
                    snapshot(target)
                    context.contentResolver.openOutputStream(uri, "w").use { out -> check(out != null); target.inputStream().use { it.copyTo(out) }; out.flush() }
                } finally { target.delete() }
            } }
            withContext(Dispatchers.Main) { Toast.makeText(context, "자료 백업을 저장했습니다.", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "백업 저장 실패. 휴대폰 자료는 그대로입니다: ${e.message}", Toast.LENGTH_LONG).show() }
        } finally {
            DataMaintenance.active = false; mutable.value = BackupStatus()
            if (paused) withContext(Dispatchers.Main) { resumeUi() }
        }
    }
    }
    suspend fun restore() = withContext(NonCancellable) { gate.withLock {
        val preview = mutable.value.preview ?: return@withLock
        mutable.value = mutable.value.copy(busy = true); DataMaintenance.active = true
        var paused = false
        try {
            paused = true; quiesce()
            withContext(Dispatchers.IO) { DataMaintenance.lock.withLock {
                // Recheck staged data immediately before changing any app file.
                preview.manifest.databases.forEach { check(inspect(it.name, File(preview.directory, "databases/${it.name}")) == it) }
                prefs.forEach { name -> check(hash(File(preview.directory, "prefs/$name.xml")) == preview.manifest.preferences[name]) }
                val folder = File(context.filesDir, "restore_backup"); check(folder.isDirectory || folder.mkdirs())
                val before = File(folder, "before_restore_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date()) + "_${UUID.randomUUID().toString().take(8)}.zip")
                snapshot(before) // MUST succeed before closing or replacing any database.
                folder.listFiles { f -> f.isFile && Regex("before_restore_\\d{8}_\\d{6}_[a-f0-9]{8}\\.zip").matches(f.name) && f != before }
                    ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { check(it.delete()) }
                val targets = databases().mapValues { (_, room) -> File(room.openHelper.writableDatabase.path) }
                val replacements = linkedMapOf<File, File>()
                targets.forEach { (name, target) -> replacements[target] = File(preview.directory, "databases/$name") }
                prefs.forEach { name -> replacements[File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")] = File(preview.directory, "prefs/$name.xml") }
                AppDatabase.closeForRestore(); InitialQuoteDatabase.closeForRestore(); CustomerInquiryDatabase.closeForRestore()
                val sidecars = targets.values.flatMap { listOf(File(it.path + "-wal"), File(it.path + "-shm")) } +
                    prefs.map { File(context.applicationInfo.dataDir, "shared_prefs/$it.xml.bak") }
                BackupRestoreJournal.install(context, replacements, sidecars) { MasterDeviceCredentials(context).inventoryLinked = false }
            } }
            preview.directory.deleteRecursively()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "복원했습니다. 동기화가 안 되면 서버 기기 연결을 열어주세요.", Toast.LENGTH_LONG).show()
                val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                val pending = PendingIntent.getActivity(context, 4720, launch, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 500, pending)
                Process.killProcess(Process.myPid())
            }
        } catch (e: Exception) {
            mutable.value = BackupStatus(message = "복원을 완료하지 못했습니다. 복원 전 백업과 복구 기록을 보존합니다: ${e.message}")
            if (paused) withContext(Dispatchers.Main) {
                Toast.makeText(context, mutable.value.message, Toast.LENGTH_LONG).show(); resumeUi()
            }
        } finally { DataMaintenance.active = false; mutable.value = mutable.value.copy(busy = false) }
    }
    }
}
