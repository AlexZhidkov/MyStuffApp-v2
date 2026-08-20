package com.azhidkov.mystuff.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredPhotoRetryTest {
    @Test
    fun `failed load retries exponentially up to existing delay bound`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val states = mutableListOf<PhotoLoadState<String>>()

        loadPhotoWithRetry(
            load = {
                attempts += 1
                if (attempts <= 6) error("Firebase unavailable")
                "decoded-photo"
            },
            wait = delays::add,
            onState = states::add,
        )

        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
        assertEquals(7, states.count { it is PhotoLoadState.Loading })
        assertEquals(6, states.count { it is PhotoLoadState.Unavailable })
        assertEquals(PhotoLoadState.Available("decoded-photo"), states.last())
    }
}
