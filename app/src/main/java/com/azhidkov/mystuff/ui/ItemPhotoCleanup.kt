package com.azhidkov.mystuff.ui

import android.content.Context
import androidx.core.net.toUri
import com.azhidkov.mystuff.ItemPhoto

/** Removes only files created by this app; picker-owned source files are left alone. */
internal fun discardUnsavedPhotoSources(
    context: Context,
    photos: Iterable<ItemPhoto>,
) {
    val ownedAuthority = "${context.packageName}.files"
    photos
        .flatMap { listOf(it.uri, it.thumbnailUri) }
        .distinct()
        .map(String::toUri)
        .filter { it.authority == ownedAuthority }
        .forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
}
