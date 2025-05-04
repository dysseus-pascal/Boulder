package io.rebble.libpebblecommon.notification

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.notification.DrawableConverter.convertDrawableToPainter

actual fun iconFor(packageName: String, appContext: AppContext): ImageBitmap? {
    return try {
        appContext.context.packageManager.getApplicationIcon(packageName).convertDrawableToPainter()
    } catch (e: Exception) {
        null
    }
}

object DrawableConverter {
    fun Drawable.convertDrawableToPainter(): ImageBitmap {
        return toBitmap().asImageBitmap()
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && this.bitmap != null) {
            return this.bitmap
        }

        val bitmap = if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            Bitmap.createBitmap(
                1,
                1,
                Bitmap.Config.ARGB_8888
            ) // Single color bitmap will be created of 1x1 pixel
        } else {
            Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)

        return bitmap
    }
}