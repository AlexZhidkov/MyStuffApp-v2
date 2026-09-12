package com.azhidkov.mystuff

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.azhidkov.mystuff.ui.ItemPhotoLoader
import com.azhidkov.mystuff.ui.itemPhotoBitmapLoader
import java.io.File

internal class SessionViewModel private constructor(
    applicationContext: Context,
) : ViewModel() {
    private val authenticationGateway = FirebaseAuthenticationGateway()
    val householdAccessGateway = FirebaseHouseholdAccessGateway()

    val itemPhotoLoader: ItemPhotoLoader<Bitmap> = itemPhotoBitmapLoader(applicationContext)
    val rootChildItemCache: RootChildItemCache = FileRootChildItemCache(
        File(applicationContext.cacheDir, ROOT_CHILD_ITEM_CACHE_DIRECTORY),
    )
    val controller = SessionController(
        authenticationGateway = authenticationGateway,
        householdGateway = FirebaseHouseholdGateway(householdAccessGateway),
        deletionGateway = FirebaseDeletionGateway(),
        sessionDataCleaner = AndroidSessionDataCleaner(
            applicationContext,
            rootChildItemCache,
        ),
        onIdentityChanged = itemPhotoLoader::onSessionChanged,
    )

    fun attach(activity: ComponentActivity) {
        authenticationGateway.attach(activity)
    }

    fun detach(activity: ComponentActivity) {
        authenticationGateway.detach(activity)
    }

    override fun onCleared() {
        controller.close()
    }

    class Factory(
        private val applicationContext: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            requireNotNull(modelClass.cast(SessionViewModel(applicationContext)))
    }
}

private const val ROOT_CHILD_ITEM_CACHE_DIRECTORY = "root-child-items"
