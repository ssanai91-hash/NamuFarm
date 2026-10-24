package com.example.namufarm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Local IDs never change: existing stock and order references remain valid. */
@Entity(tableName = "master_links", primaryKeys = ["kind", "local_id"],
    indices = [Index(value = ["kind", "remote_id"], unique = true)])
data class MasterLinkEntity(
    val kind: String,
    @ColumnInfo(name = "local_id") val localId: Int,
    @ColumnInfo(name = "remote_id") val remoteId: Int,
    val version: String
)

/** Shared by reconciliation and its JVM tests. Never match unrelated numeric IDs. */
object MasterIdentity {
    fun localId(remoteId: Int, name: String, links: List<MasterLinkEntity>,
                localNames: List<Pair<Int, String>>): Int? {
        links.firstOrNull { it.remoteId == remoteId }?.let { return it.localId }
        val linked = links.map { it.localId }.toSet()
        val matches = localNames.filter { it.first !in linked && it.second == name }
        require(matches.size <= 1) { "이름이 중복되어 자동 연결할 수 없습니다: $name" }
        return matches.singleOrNull()?.first
    }
}
