package com.azhidkov.mystuff

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors

internal interface RootChildItemCache {
    fun load(householdId: String): List<Item>?

    fun store(householdId: String, items: List<Item>)
}

internal object NoRootChildItemCache : RootChildItemCache {
    override fun load(householdId: String): List<Item>? = null

    override fun store(householdId: String, items: List<Item>) = Unit
}

internal class FileRootChildItemCache(
    private val directory: File,
    private val scheduleWrite: ((() -> Unit) -> Unit) = { task ->
        rootChildItemCacheWriter.execute(task)
    },
) : RootChildItemCache {
    private val memory = mutableMapOf<String, List<Item>>()

    override fun load(householdId: String): List<Item>? {
        synchronized(memory) {
            if (memory.containsKey(householdId)) return memory.getValue(householdId)
        }

        val cacheFile = directory.resolve(rootChildItemCacheFileName(householdId))
        if (!cacheFile.isFile) return null
        return try {
            read(cacheFile, householdId).also { items ->
                synchronized(memory) { memory[householdId] = items }
            }
        } catch (_: Exception) {
            cacheFile.delete()
            null
        }
    }

    override fun store(householdId: String, items: List<Item>) {
        val snapshot = items.map { item -> item.copy(tags = item.tags.toList()) }
        synchronized(memory) { memory[householdId] = snapshot }
        scheduleWrite {
            try {
                writeAtomically(householdId, snapshot)
            } catch (_: Exception) {
                // Disk caching is best effort; the in-memory snapshot remains useful.
            }
        }
    }

    private fun read(cacheFile: File, expectedHouseholdId: String): List<Item> =
        DataInputStream(BufferedInputStream(FileInputStream(cacheFile))).use { input ->
            require(input.readInt() == CACHE_MAGIC)
            require(input.readInt() == CACHE_VERSION)
            require(input.readUTF() == expectedHouseholdId)
            val itemCount = input.readInt()
            require(itemCount >= 0)
            buildList {
                repeat(itemCount) {
                    add(
                        Item(
                            id = input.readUTF(),
                            name = input.readUTF(),
                            parentItemId = input.readNullableString(),
                            photoAttachmentId = input.readNullableString(),
                            photoUrl = input.readNullableString(),
                            description = input.readNullableString(),
                            tags = buildList {
                                val tagCount = input.readInt()
                                require(tagCount >= 0)
                                repeat(tagCount) { add(input.readUTF()) }
                            },
                            photoThumbnailUrl = input.readNullableString(),
                            webUrl = input.readNullableString(),
                            displayOrder = input.readNullableLong(),
                        ),
                    )
                }
            }.also { items ->
                require(items.all { item ->
                    item.id != expectedHouseholdId && item.parentItemId == expectedHouseholdId
                })
                require(items.distinctBy(Item::id).size == items.size)
                require(input.read() == -1)
            }
        }

    private fun writeAtomically(householdId: String, items: List<Item>) {
        directory.mkdirs()
        val cacheFile = directory.resolve(rootChildItemCacheFileName(householdId))
        val temporaryFile = File.createTempFile(cacheFile.name, ".part", directory)
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporaryFile))).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeInt(CACHE_VERSION)
                output.writeUTF(householdId)
                output.writeInt(items.size)
                items.forEach { item ->
                    require(item.parentItemId == householdId)
                    output.writeUTF(item.id)
                    output.writeUTF(item.name)
                    output.writeNullableString(item.parentItemId)
                    output.writeNullableString(item.photoAttachmentId)
                    output.writeNullableString(item.photoUrl)
                    output.writeNullableString(item.description)
                    output.writeInt(item.tags.size)
                    item.tags.forEach(output::writeUTF)
                    output.writeNullableString(item.photoThumbnailUrl)
                    output.writeNullableString(item.webUrl)
                    output.writeNullableLong(item.displayOrder)
                }
            }
            try {
                Files.move(
                    temporaryFile.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporaryFile.delete()
        }
    }
}

private fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readUTF() else null

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    value?.let(::writeUTF)
}

private fun DataInputStream.readNullableLong(): Long? =
    if (readBoolean()) readLong() else null

private fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    value?.let(::writeLong)
}

internal fun rootChildItemCacheFileName(householdId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(householdId.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "$digest.items"
}

private val rootChildItemCacheWriter = Executors.newSingleThreadExecutor { task ->
    Thread(task, "root-child-item-cache").apply { isDaemon = true }
}

private const val CACHE_MAGIC = 0x4D595354
private const val CACHE_VERSION = 5
