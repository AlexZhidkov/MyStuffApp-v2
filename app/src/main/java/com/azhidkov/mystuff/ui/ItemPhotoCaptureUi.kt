@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.azhidkov.mystuff.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.azhidkov.mystuff.InventoryActions
import com.azhidkov.mystuff.ItemFormStage
import com.azhidkov.mystuff.ItemPhoto
import com.azhidkov.mystuff.R
import java.io.File
import java.util.UUID

@Composable
internal fun CameraCaptureStep(
    stage: ItemFormStage,
    unsavedPhotos: List<ItemPhoto>,
    actions: InventoryActions,
) {
    val context = LocalContext.current
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        actions::resolveCameraPermission,
    )
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val uri = pendingPhotoUri
        if (captured && uri != null) {
            actions.photoCaptured(ItemPhoto(uri.toString()))
        } else {
            uri?.let { discardUnsavedPhotoSources(context, listOf(ItemPhoto(it.toString()))) }
            actions.photoCaptureFailed()
        }
    }

    val cancel = {
        pendingPhotoUri?.let {
            discardUnsavedPhotoSources(context, listOf(ItemPhoto(it.toString())))
        }
        discardUnsavedPhotoSources(context, unsavedPhotos)
        actions.closeItemForm()
    }
    BackHandler(onBack = cancel)

    LaunchedEffect(stage) {
        when (stage) {
            ItemFormStage.CameraPermission -> {
                val cameraAvailable = context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_CAMERA_ANY,
                )
                if (cameraAvailable) {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    actions.cameraUnavailable()
                }
            }

            ItemFormStage.Camera -> {
                runCatching {
                    createTemporaryPhotoUri(context).also { uri ->
                        pendingPhotoUri = uri
                        cameraLauncher.launch(uri)
                    }
                }.onFailure {
                    pendingPhotoUri?.let {
                        discardUnsavedPhotoSources(context, listOf(ItemPhoto(it.toString())))
                    }
                    actions.photoCaptureFailed()
                }
            }

            else -> Unit
        }
    }

    ItemCreationMessageScreen(
        title = stringResource(R.string.opening_camera),
        body = stringResource(R.string.opening_camera_body),
        onCancel = cancel,
    )
}

@Composable
private fun ItemCreationMessageScreen(
    title: String,
    body: String,
    onCancel: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun createTemporaryPhotoUri(context: Context): Uri {
    val directory = File(context.cacheDir, "item-photos").apply { mkdirs() }
    val file = File(directory, "captured-${UUID.randomUUID()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
