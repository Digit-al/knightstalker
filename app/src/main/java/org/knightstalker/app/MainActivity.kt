// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.knightstalker.app.ui.navigation.KnightNavGraph
import org.knightstalker.app.ui.theme.KnightStalkerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KnightStalkerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KnightNavGraph()
                }
            }
        }
    }
}
