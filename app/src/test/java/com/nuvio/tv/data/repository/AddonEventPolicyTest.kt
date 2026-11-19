package com.nuvio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonEventPolicyTest {
    private val none: (String) -> Boolean = { false }

    @Test
    fun `own source and server items are never described to a tracker`() {
        assertFalse("server id", AddonEventPolicy.mayDescribe("srv:abc", { it.startsWith("srv:") }, none, none))
        assertFalse("own content id", AddonEventPolicy.mayDescribe("xtream:12", none, { it.startsWith("xtream:") }, none))
        assertFalse("scrobble excluded", AddonEventPolicy.mayDescribe("tt1", none, none, { it == "tt1" }))
        assertFalse("blank", AddonEventPolicy.mayDescribe("", none, none, none))
        assertTrue("public id", AddonEventPolicy.mayDescribe("tt0898266", none, none, none))
    }
}
