package com.scoring_offline.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scoring_offline.Amber
import com.scoring_offline.Bad
import com.scoring_offline.Good

private val Steps = listOf(
    "On the TV, open the Play Store and install the free app Downloader (by AFTVnews).",
    "Open Downloader and type the address above.",
    "When the TV asks, allow Downloader to install apps, then choose Install.",
    "Open Scorer on the TV. This phone connects to it automatically.",
    "On someone else's TV: turn \"Install unknown apps\" off again for Downloader.",
)

/** The APK is served only while this screen is open. */
@Composable
internal fun InstallTvScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val share = remember { ApkShare(context) }
    DisposableEffect(Unit) {
        share.start()
        onDispose { share.stop() }
    }
    val state by share.state.collectAsState()

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Install on a new TV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("Close") }
        }
        Text(
            "Phone and TV must be on the same Wi-Fi. Keep this screen open until the TV finishes installing.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Type this on the TV", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                when {
                    state.error != null -> Text(state.error!!, color = Bad, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    state.urls.isEmpty() -> Text("Starting…", fontSize = 18.sp)
                    else -> state.urls.forEach { url ->
                        Text(
                            url, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            color = Amber, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        if (state.downloads > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Good, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("App sent to the TV. Finish the install there.", color = Good, fontWeight = FontWeight.Bold)
            }
        }

        Steps.forEachIndexed { i, step ->
            Row {
                Box(
                    Modifier.size(28.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                Spacer(Modifier.width(12.dp))
                Text(step, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(top = 3.dp))
            }
        }

        Text(
            "Works on Android TV and Google TV only.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
