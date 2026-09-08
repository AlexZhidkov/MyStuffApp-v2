package com.azhidkov.mystuff.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azhidkov.mystuff.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpeningHouseholdScreen(
    opening: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    AppBarOverflowMenu(
                        enabled = !opening,
                        onSignOut = onSignOut,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .widthIn(max = 720.dp),
        ) {
            if (opening) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(
                    if (opening) {
                        R.string.opening_household
                    } else {
                        R.string.open_household_failed
                    },
                ),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            if (opening) {
                HouseholdSkeleton()
            } else {
                errorMessage?.let { detail ->
                    Text(
                        text = detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(20.dp))
                }
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun HouseholdSkeleton() {
    Column {
        SkeletonBar(widthFraction = 0.58f, height = 30)
        Spacer(Modifier.height(18.dp))
        SkeletonBar(widthFraction = 1f, height = 88)
        Spacer(Modifier.height(12.dp))
        SkeletonBar(widthFraction = 1f, height = 88)
        Spacer(Modifier.height(12.dp))
        SkeletonBar(widthFraction = 0.82f, height = 88)
    }
}

@Composable
private fun SkeletonBar(
    widthFraction: Float,
    height: Int,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {}
}
