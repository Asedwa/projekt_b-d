package org.kde.plasmawave.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

fun isPngUri(context: Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val header = ByteArray(8)
            val bytesRead = stream.read(header)
            if (bytesRead < 8) return false
            // PNG magic: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte() &&
            header[4] == 0x0D.toByte() &&
            header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() &&
            header[7] == 0x0A.toByte()
        } ?: false
    } catch (_: Exception) {
        false
    }
}

@Composable
fun ImageCropperDialog(
    imageUri: Uri,
    onDismiss: () -> Unit,
    onCroppedSaved: (File) -> Unit
) {
    val context = LocalContext.current
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var isSaving by remember { mutableStateOf(false) }

    val cropBoxSizeDp = 260.dp
    val density = LocalDensity.current
    val cropBoxPx = with(density) { cropBoxSizeDp.toPx() }

    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(imageUri)?.use { stream ->
                    originalBitmap = BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Görsel yüklenemedi: ${e.message}", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF101015)
        ) {
            if (originalBitmap == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                val bmp = originalBitmap!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Kapat", tint = Color.White)
                        }
                        Text(
                            "PNG Logoyu Kırp ve Konumlandır",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(
                            onClick = {
                                if (isSaving) return@IconButton
                                isSaving = true
                                cropAndSave(
                                    context = context,
                                    bmp = bmp,
                                    scale = scale,
                                    offset = offset,
                                    rotation = rotation,
                                    cropBoxPx = cropBoxPx
                                ) { file ->
                                    isSaving = false
                                    if (file != null) {
                                        Toast.makeText(context, "Özel logo kaydedildi!", Toast.LENGTH_SHORT).show()
                                        onCroppedSaved(file)
                                    } else {
                                        Toast.makeText(context, "Kırpma başarısız oldu", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Icon(Icons.Default.Check, contentDescription = "Kaydet", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Görseli sürükleyerek ortalayın, alttaki kontrollerle yakınlaştırıp çevirin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(20.dp))

                    // Crop area viewport
                    Box(
                        modifier = Modifier
                            .size(cropBoxSizeDp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clipToBounds()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, rot ->
                                    scale = (scale * zoom).coerceIn(0.4f, 5.0f)
                                    offset += pan
                                    rotation = (rotation + rot) % 360f
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Düzenlenecek PNG",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                    rotationZ = rotation
                                }
                        )

                        // Subtle crosshair guides
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.2f))
                        )
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(Color.White.copy(alpha = 0.2f))
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // Live Header Preview
                    Text("Canlı Başlık Önizlemesi", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E1E28))
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                            .clipToBounds(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val previewRatio = 64f / cropBoxPx
                                    scaleX = scale * previewRatio
                                    scaleY = scale * previewRatio
                                    translationX = offset.x * previewRatio
                                    translationY = offset.y * previewRatio
                                    rotationZ = rotation
                                }
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Controls panel
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1C1B26))
                            .padding(16.dp)
                    ) {
                        // Zoom / Scale row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Boyut", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(60.dp))
                            IconButton(onClick = { scale = (scale - 0.1f).coerceAtLeast(0.4f) }) {
                                Icon(Icons.Default.Remove, contentDescription = "Küçült", tint = Color.LightGray)
                            }
                            Slider(
                                value = scale,
                                onValueChange = { scale = it },
                                valueRange = 0.4f..4.0f,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { scale = (scale + 0.1f).coerceAtMost(4.0f) }) {
                                Icon(Icons.Default.Add, contentDescription = "Büyüt", tint = Color.LightGray)
                            }
                        }

                        // Rotate button and reset
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    offset = Offset.Zero
                                    scale = 1.0f
                                    rotation = 0f
                                }
                            ) {
                                Text("Konumu Sıfırla")
                            }

                            Button(
                                onClick = { rotation = (rotation + 90f) % 360f }
                            ) {
                                Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("90° Döndür")
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            if (isSaving) return@Button
                            isSaving = true
                            cropAndSave(
                                context = context,
                                bmp = bmp,
                                scale = scale,
                                offset = offset,
                                rotation = rotation,
                                cropBoxPx = cropBoxPx
                            ) { file ->
                                isSaving = false
                                if (file != null) {
                                    Toast.makeText(context, "Özel PNG logosu başarıyla ayarlandı!", Toast.LENGTH_SHORT).show()
                                    onCroppedSaved(file)
                                } else {
                                    Toast.makeText(context, "Kırpma başarısız oldu", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Kırp ve Logoyu Uygula", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun cropAndSave(
    context: Context,
    bmp: Bitmap,
    scale: Float,
    offset: Offset,
    rotation: Float,
    cropBoxPx: Float,
    onResult: (File?) -> Unit
) {
    try {
        val targetSize = 512
        val resultBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBmp)

        val matrix = Matrix()
        // Center of target
        val targetCenter = targetSize / 2f
        // Scale factor from cropBoxPx to targetSize
        val viewToTarget = targetSize / cropBoxPx

        // Translate to target center
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postRotate(rotation)
        matrix.postScale(scale * (cropBoxPx / bmp.width) * viewToTarget, scale * (cropBoxPx / bmp.height) * viewToTarget)
        matrix.postTranslate(targetCenter + offset.x * viewToTarget, targetCenter + offset.y * viewToTarget)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bmp, matrix, paint)

        val outputFile = File(context.filesDir, "custom_header_logo.png")
        FileOutputStream(outputFile).use { out ->
            resultBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        onResult(outputFile)
    } catch (e: Exception) {
        onResult(null)
    }
}
