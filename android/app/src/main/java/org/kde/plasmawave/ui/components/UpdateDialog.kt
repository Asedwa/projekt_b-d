package org.kde.plasmawave.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.kde.plasmawave.BuildConfig
import org.kde.plasmawave.theme.LocalPlasmaColors
import org.kde.plasmawave.update.AppUpdateRelease
import org.kde.plasmawave.update.AppUpdater
import org.kde.plasmawave.update.InstallLaunchResult
import org.kde.plasmawave.update.UpdateState
import java.io.File
import java.util.Locale

private fun formatMegabytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.1f MB", mb)
}

@Composable
fun UpdateDialog(
    state: UpdateState,
    onStartDownload: (AppUpdateRelease) -> Unit,
    onCancelDownload: () -> Unit,
    onInstallApk: (File) -> InstallLaunchResult,
    onSkipVersion: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalPlasmaColors.current
    val context = LocalContext.current

    when (state) {
        is UpdateState.Available -> {
            val release = state.release
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = "Yeni sürüm hazır",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.cardPressed
                            ) {
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME} → v${release.version}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                            if (release.apkSizeBytes > 0L) {
                                Text(
                                    text = formatMegabytes(release.apkSizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        if (release.notes.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.cardPressed.copy(alpha = 0.5f))
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = release.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        TextButton(
                            onClick = { onSkipVersion(release.version) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Bu sürümü atla",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { onStartDownload(release) }) {
                        Text("İndir ve Güncelle")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Daha Sonra")
                    }
                }
            )
        }

        is UpdateState.Downloading -> {
            val fraction = (state.progressPercent / 100f).coerceIn(0f, 1f)
            AlertDialog(
                onDismissRequest = {},
                icon = {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = "v${state.release.version} indiriliyor...",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(50))
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${formatMegabytes(state.downloadedBytes)} / ${formatMegabytes(state.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = "%${state.progressPercent}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(50))
                            )
                            Text(
                                text = formatMegabytes(state.downloadedBytes).ifEmpty { "İndiriliyor..." },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onCancelDownload) {
                        Text("İptal")
                    }
                }
            )
        }

        is UpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = "Kuruluma hazır",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "projekt_b-d v${state.release.version} indirildi. Kurulumu başlatmak için dokun. Android ilk kez yükleme izni isterse izin verip geri dönmen yeterli.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            when (val result = onInstallApk(state.apkFile)) {
                                InstallLaunchResult.Launched -> onDismiss()
                                InstallLaunchResult.NeedsPermission -> {
                                    Toast.makeText(
                                        context,
                                        "Uygulama yükleme iznini açıp geri dön",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                is InstallLaunchResult.Failed -> {
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Text("Şimdi Kur")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Kapat")
                    }
                }
            )
        }

        is UpdateState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = {
                    Text(
                        text = "Güncelleme kontrol edilemedi",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdater.RELEASES_PAGE_URL))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            onDismiss()
                        }
                    ) {
                        Text("GitHub'da Aç")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Tamam")
                    }
                }
            )
        }

        else -> Unit
    }
}
