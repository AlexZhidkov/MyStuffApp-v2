package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test

class DescriptionGenerationModelConfigTest {
    @Test
    fun `configured model name is used`() {
        val config = RemoteConfigDescriptionGenerationModelConfig(
            source = {
                FetchedDescriptionGenerationModel(
                    name = "  gemini-configured  ",
                    fetchedAtMillis = NOW_MILLIS - 1,
                )
            },
            nowMillis = { NOW_MILLIS },
            maxAgeMillis = MAX_AGE_MILLIS,
        )

        assertEquals("gemini-configured", config.modelName())
    }

    @Test
    fun `bundled model is used when parameter is absent`() {
        val config = modelConfig(name = null)

        assertEquals("gemini-3.7-flash", config.modelName())
    }

    @Test
    fun `bundled model is used when parameter is blank`() {
        val config = modelConfig(name = " \n\t ")

        assertEquals("gemini-3.7-flash", config.modelName())
    }

    @Test
    fun `bundled model is used when fetched parameter is stale`() {
        val config = modelConfig(
            name = "gemini-stale",
            fetchedAtMillis = NOW_MILLIS - MAX_AGE_MILLIS - 1,
        )

        assertEquals("gemini-3.7-flash", config.modelName())
    }

    @Test
    fun `bundled model is used when Remote Config is unavailable`() {
        val config = RemoteConfigDescriptionGenerationModelConfig(
            source = { error("Remote Config unavailable") },
            nowMillis = { NOW_MILLIS },
            maxAgeMillis = MAX_AGE_MILLIS,
        )

        assertEquals("gemini-3.7-flash", config.modelName())
    }

    private fun modelConfig(
        name: String?,
        fetchedAtMillis: Long = NOW_MILLIS,
    ) = RemoteConfigDescriptionGenerationModelConfig(
        source = { FetchedDescriptionGenerationModel(name, fetchedAtMillis) },
        nowMillis = { NOW_MILLIS },
        maxAgeMillis = MAX_AGE_MILLIS,
    )
}

private const val NOW_MILLIS = 100_000L
private const val MAX_AGE_MILLIS = 10_000L
