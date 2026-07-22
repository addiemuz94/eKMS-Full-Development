package com.ekms.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CardUidResolutionTest {
    @Test
    fun `matches a personnel card to login`() {
        assertEquals(CardUidMatch.User("user-1"), CardUidResolver.resolve(matchedUserId = "user-1", matchedKeyId = null))
    }

    @Test
    fun `matches a key card to a return trigger`() {
        assertEquals(CardUidMatch.Key("key-1"), CardUidResolver.resolve(matchedUserId = null, matchedKeyId = "key-1"))
    }

    @Test
    fun `an unenrolled card matches nothing`() {
        assertEquals(CardUidMatch.NoMatch, CardUidResolver.resolve(matchedUserId = null, matchedKeyId = null))
    }

    @Test
    fun `a card enrolled to both a person and a key is ambiguous, not silently one or the other`() {
        val result = CardUidResolver.resolve(matchedUserId = "user-1", matchedKeyId = "key-1")
        assertEquals(CardUidMatch.Ambiguous("user-1", "key-1"), result)
    }
}
