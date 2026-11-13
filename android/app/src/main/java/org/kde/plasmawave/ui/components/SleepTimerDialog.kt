package org.kde.plasmawave.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.player.SleepTimerState
import org.kde.plasmawave.player.formatSleepTimerRemaining
import org.kde.plasmawave.player.isActive
import org.kde.plasmawave.theme.LocalPlasmaColors

private val SLEEP_PRESET_MINUTES = listOf(5, 15, 30, 45, 60)

@Composable
fun SleepTimerDialog(
    sleepTimerState: SleepTimerState,
    onSelectMinutes: (Int) -> Unit,
    onSelectEndOfTrack: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalPlasmaColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Uyku zamanı modu") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val statusText = when (sleepTimerState) {
                    SleepTimerState.Off -> "Belirlenen süre sonunda oynatma otomatik olarak duraklatılır."
                    is SleepTimerState.Countdown -> "Kalan süre: ${formatSleepTimerRemaining(sleepTimerState.remainingMs)}"
                    SleepTimerState.EndOfTrack -> "Mevcut şarkı bittiğinde oynatma duraklatılacak."
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (sleepTimerState.isActive()) MaterialTheme.colorScheme.primary else colors.textSecondary,
                    fontWeight = if (sleepTimerState.isActive()) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(Modifier.height(12.dp))
                SLEEP_PRESET_MINUTES.forEach { minutes ->
                    SleepTimerOptionRow(
                        icon = Icons.Default.Timer,
                        label = "$minutes dakika",
                        selected = false,
                        onClick = {
                            onSelectMinutes(minutes)
                            onDismiss()
                        }
                    )
                }
                SleepTimerOptionRow(
                    icon = Icons.Default.MusicNote,
                    label = "Bu şarkı bitince",
                    selected = sleepTimerState is SleepTimerState.EndOfTrack,
                    onClick = {
                        onSelectEndOfTrack()
                        onDismiss()
                    }
                )
            }
        },
        dismissButton = if (sleepTimerState.isActive()) {
            {
                TextButton(onClick = {
                    onCancelTimer()
                    onDismiss()
                }) {
                    Text("Zamanlayıcıyı kapat", color = MaterialTheme.colorScheme.error)
                }
            }
        } else null,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}

@Composable
private fun SleepTimerOptionRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalPlasmaColors.current
    val tint = if (selected) MaterialTheme.colorScheme.primary else colors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
