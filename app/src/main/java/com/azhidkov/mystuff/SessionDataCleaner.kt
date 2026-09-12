package com.azhidkov.mystuff

import android.content.Context
import androidx.work.WorkManager
import java.io.File

fun interface SessionDataCleaner {
    fun clear()
}

internal object NoSessionDataCleaner : SessionDataCleaner {
    override fun clear() = Unit
}

internal class AndroidSessionDataCleaner(
    context: Context,
    private val rootChildItemCache: RootChildItemCache,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : SessionDataCleaner {
    private val applicationContext = context.applicationContext

    override fun clear() {
        workManager.cancelAllWork()
        rootChildItemCache.clear()
        listOf(
            File(applicationContext.noBackupFilesDir, DESCRIPTION_GENERATION_WORK_DIRECTORY),
            File(applicationContext.filesDir, ITEM_PHOTO_DIRECTORY),
            File(applicationContext.cacheDir, ITEM_PHOTO_DIRECTORY),
        ).forEach(File::deleteRecursively)
    }
}

private const val ITEM_PHOTO_DIRECTORY = "item-photos"
