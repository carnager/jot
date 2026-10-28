package io.github.carnager.jot

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.ui.CaptureContext
import io.github.carnager.jot.ui.CaptureField
import io.github.carnager.jot.ui.Fmt
import io.github.carnager.jot.ui.theme.JotTheme

/**
 * The floating capture sheet behind the Quick Settings tile, launcher shortcuts, the widget's +
 * and the share target. The keyboard is up at once; Enter saves and closes.
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_NOTE
        if (mode == MODE_LIST) {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_LIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
            return
        }
        val shared = if (intent.action == Intent.ACTION_SEND) {
            listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                .filter { it.isNotBlank() }.distinct().joinToString("\n")
        } else ""
        val ctx = if (mode == MODE_TODAY) CaptureContext(dueToday = true, hint = "Add a task for today…")
        else CaptureContext(hint = "Note or task… “call Anna fri 5pm”")

        setContent {
            val prefs by Repo.settings.prefs.collectAsState()
            JotTheme(prefs.theme, prefs.dynamicColor) {
                val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                        .clickable(remember { MutableInteractionSource() }, null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 2 }) {
                        Surface(
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {},
                        ) {
                            Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                                    Text(
                                        if (shared.isNotEmpty()) "Save to Jot" else "Quick add",
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = {
                                        startActivity(Intent(this@CaptureActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        finish()
                                    }) { Text("Open Jot") }
                                }
                                CaptureField(
                                    ctx,
                                    initialText = shared,
                                    autoFocus = true,
                                    onSubmit = { item ->
                                        Repo.save(item)
                                        val where = if (item.due.isNotEmpty()) Fmt.dueShort(item.due) else "Notes"
                                        Toast.makeText(this@CaptureActivity, "Saved · $where", Toast.LENGTH_SHORT).show()
                                        finish()
                                    },
                                    onExpand = { item ->
                                        Repo.save(item)
                                        startActivity(
                                            Intent(this@CaptureActivity, MainActivity::class.java)
                                                .putExtra(io.github.carnager.jot.notify.Reminders.EXTRA_ID, item.id)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                        )
                                        finish()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_NOTE = "note"
        const val MODE_LIST = "list"
        const val MODE_TODAY = "today"
    }
}
