package com.azhidkov.mystuff.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.azhidkov.mystuff.R

@Composable
internal fun AppBarBrandIcon(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_app_logo_generated),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(48.dp),
        )
    }
}
