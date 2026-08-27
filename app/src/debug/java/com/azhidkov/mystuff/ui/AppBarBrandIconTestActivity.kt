package com.azhidkov.mystuff.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.azhidkov.mystuff.ui.theme.MyStuffTheme

class AppBarBrandIconTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyStuffTheme {
                AppBarBrandIcon(onClick = {})
            }
        }
    }
}
