// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.knightstalker.app.ui.navigation.KnightNavGraph
import org.knightstalker.app.ui.navigation.remoteKeyHandler
import org.knightstalker.app.ui.theme.KnightStalkerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KnightStalkerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KnightNavGraph()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d("KSKey", "keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")
            if (remoteKeyHandler?.invoke(event.keyCode) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
