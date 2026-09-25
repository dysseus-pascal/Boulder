package coredevices.ring.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image.Companion.makeFromEncoded
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

@Composable
actual fun QrCodeImage(
    data: String,
    modifier: Modifier,
    size: Dp,
) {
    val bitmap = remember(data) {
        generateQrImage(data)
            ?.let { UIImagePNGRepresentation(it) }
            ?.let { png ->
                val bytes = ByteArray(png.length.toInt())
                if (bytes.isNotEmpty()) {
                    bytes.usePinned { memcpy(it.addressOf(0), png.bytes, png.length) }
                }
                makeFromEncoded(bytes).toComposeImageBitmap()
            }
    }
    if (bitmap != null) {
        Box(
            modifier = modifier
                .size(size)
                .background(Color.White)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Could not render QR code.\nUse Copy Key instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
