package com.azhidkov.mystuff

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RootChildItemCacheTest {
    @Test
    fun `root Child Item fields survive an app restart`() = withTemporaryDirectory { directory ->
        val item = Item(
            id = "garage",
            name = "Garage",
            parentItemId = "household-1",
            photoUrl = "gs://mystuff/garage.webp",
            description = "West side",
            tags = listOf("Storage", "Tools"),
            photoThumbnailUrl = "gs://mystuff/garage-thumb.webp",
        )
        FileRootChildItemCache(directory) { task -> task() }
            .store("household-1", listOf(item))

        val restartedCache = FileRootChildItemCache(directory) { task -> task() }

        assertEquals(listOf(item), restartedCache.load("household-1"))
    }

    @Test
    fun `an empty root is cached distinctly from a cache miss`() =
        withTemporaryDirectory { directory ->
            FileRootChildItemCache(directory) { task -> task() }
                .store("household-1", emptyList())

            val restartedCache = FileRootChildItemCache(directory) { task -> task() }

            assertEquals(emptyList<Item>(), restartedCache.load("household-1"))
            assertNull(restartedCache.load("household-2"))
        }

    @Test
    fun `a corrupt root Child Item snapshot is deleted and treated as a miss`() =
        withTemporaryDirectory { directory ->
            val cacheFile = directory.resolve(rootChildItemCacheFileName("household-1"))
            directory.mkdirs()
            cacheFile.writeText("truncated")
            val cache = FileRootChildItemCache(directory) { task -> task() }

            assertNull(cache.load("household-1"))
            assertFalse(cacheFile.exists())
        }

    private fun withTemporaryDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("root-child-item-cache-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
