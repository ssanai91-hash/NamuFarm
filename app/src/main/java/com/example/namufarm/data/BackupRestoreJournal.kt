package com.example.namufarm.data

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Multi-file replacement is recoverable after process death, including between individual renames. */
object BackupRestoreJournal {
    private data class Move(val target: String, val staged: String?, val old: String, val hadOriginal: Boolean)
    private data class Journal(val phase: String, val moves: List<Move>)
    private fun journal(context: Context) = File(context.filesDir, "restore-journal.json")
    private fun write(file: File, value: Journal) {
        val temp = File(file.parentFile, "restore-journal.tmp")
        FileOutputStream(temp).use { it.write(Gson().toJson(value).toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temp.renameTo(file)) { "복원 기록을 저장하지 못했습니다." }
    }
    fun recover(context: Context) {
        val file = journal(context)
        if (!file.exists()) return
        val value = Gson().fromJson(file.readText(), Journal::class.java)
        val root = context.applicationInfo.dataDir.let(::File).canonicalFile
        value.moves.forEach { move ->
            listOfNotNull(move.target, move.staged, move.old).forEach {
                check(File(it).canonicalPath.startsWith(root.path + File.separator)) { "복원 경로를 확인할 수 없습니다." }
            }
        }
        if (value.phase != "committed") rollback(value)
        cleanup(value)
        check(file.delete()) { "복원 기록을 마무리하지 못했습니다." }
    }
    private fun rollback(value: Journal) {
        value.moves.asReversed().forEach { move ->
            val target = File(move.target); val old = File(move.old)
            if (old.exists()) {
                check(!target.exists() || target.delete()) { "복원 취소 중 새 파일을 정리하지 못했습니다." }
                check(old.renameTo(target)) { "원래 자료를 되돌리지 못했습니다. 복원 기록을 보존했습니다." }
            } else if (!move.hadOriginal && move.staged != null && !File(move.staged).exists()) {
                check(!target.exists() || target.delete())
            }
        }
    }
    private fun cleanup(value: Journal) {
        value.moves.forEach { move ->
            listOfNotNull(move.old, move.staged).forEach { path ->
                val f = File(path); check(!f.exists() || f.delete()) { "복원 임시 파일 정리 실패" }
            }
        }
    }
    fun install(context: Context, replacements: Map<File, File>, sidecars: List<File>, resetInventory: () -> Unit) {
        check(!journal(context).exists()) { "이전 복원을 먼저 복구해야 합니다." }
        val id = UUID.randomUUID().toString()
        val staged = linkedMapOf<File, File>()
        try {
            replacements.forEach { (target, source) ->
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                val temp = File(target.parentFile, ".restore-$id-${target.name}.new")
                check(temp.createNewFile())
                source.inputStream().use { input -> FileOutputStream(temp).use { out -> input.copyTo(out); out.fd.sync() } }
                staged[target] = temp
            }
            val moves = (staged.keys + sidecars).distinct().map { target -> Move(target.absolutePath,
                staged[target]?.absolutePath, File(target.parentFile, ".restore-$id-${target.name}.old").absolutePath, target.exists()) }
            val value = Journal("installing", moves)
            write(journal(context), value)
            try {
                moves.forEach { move ->
                    val target = File(move.target)
                    if (move.hadOriginal) check(target.renameTo(File(move.old))) { "현재 자료를 보관하지 못했습니다." }
                    move.staged?.let { check(File(it).renameTo(target)) { "백업 자료를 설치하지 못했습니다." } }
                }
                resetInventory()
                write(journal(context), value.copy(phase = "committed"))
            } catch (e: Exception) {
                rollback(value) // A failed rollback retains the journal for the next launch.
                cleanup(value)
                check(journal(context).delete())
                throw e
            }
            // Replacement is already committed; cleanup failure must still lead to a process restart.
            runCatching { cleanup(value); check(journal(context).delete()) }
        } catch (e: Exception) {
            if (!journal(context).exists()) staged.values.forEach { it.delete() }
            throw e
        }
    }
}
