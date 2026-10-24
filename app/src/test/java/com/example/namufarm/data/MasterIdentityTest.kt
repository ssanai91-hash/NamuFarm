package com.example.namufarm.data

import org.junit.Assert.*
import org.junit.Test

class MasterIdentityTest {
    @Test fun numericIdCollisionNeverReusesUnrelatedLocalFarm() {
        assertNull(MasterIdentity.localId(1, "새 농원", emptyList(), listOf(1 to "기존 농원")))
    }
    @Test fun renameKeepsLocalInventoryReferences() {
        val links = listOf(MasterLinkEntity("farm", 7, 90, "version"))
        assertEquals(7, MasterIdentity.localId(90, "바뀐 농원명", links, listOf(7 to "이전 농원명")))
    }
    @Test fun bootstrapMatchesNameWithoutChangingLocalId() {
        assertEquals(12, MasterIdentity.localId(186, "가죽나무", emptyList(), listOf(12 to "가죽나무")))
    }
    @Test fun linkedNameCannotBeReusedForAnotherRemoteId() {
        val links = listOf(MasterLinkEntity("species", 12, 186, "version"))
        assertNull(MasterIdentity.localId(200, "가죽나무", links, listOf(12 to "가죽나무")))
    }
    @Test(expected = IllegalArgumentException::class)
    fun duplicateLocalNamesRequireResolution() {
        MasterIdentity.localId(200, "중복", emptyList(), listOf(1 to "중복", 2 to "중복"))
    }
}
