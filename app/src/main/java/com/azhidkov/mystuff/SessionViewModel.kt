package com.azhidkov.mystuff

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.azhidkov.mystuff.ui.ItemPhotoLoader
import com.azhidkov.mystuff.ui.itemPhotoBitmapLoader

internal class SessionViewModel private constructor(
    applicationContext: Context,
    invitationId: String?,
) : ViewModel() {
    private val authenticationGateway = FirebaseAuthenticationGateway()

    val itemPhotoLoader: ItemPhotoLoader<Bitmap> = itemPhotoBitmapLoader(applicationContext)
    val controller = SessionController(
        authenticationGateway = authenticationGateway,
        householdGateway = FirebaseHouseholdGateway(),
        invitationAcceptanceGateway = FirebaseInvitationAcceptanceGateway(),
        invitationId = invitationId,
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
        private val invitationId: String?,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            requireNotNull(modelClass.cast(SessionViewModel(applicationContext, invitationId)))
    }
}
