package com.riyadm.unsendvault.ui.components

import android.content.Context
import com.riyadm.unsendvault.apps.AppDirectory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small in-memory cache of launcher icons. Misses are cached too, so absent packages are looked up once. */
internal object AppIconCache {
    private object Missing

    private val cache = LruCache<String, Any>(96)

    fun peek(packageName: String, sizePx: Int): ImageBitmap? = cache.get("$packageName@$sizePx") as? ImageBitmap

    fun load(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        val key = "$packageName@$sizePx"
        cache.get(key)?.let { return it as? ImageBitmap }
        // Current profile first, then the badged icon of the app in a work/other profile.
        val bitmap = try {
            AppDirectory(context).icon(packageName)?.toBitmap(sizePx, sizePx)?.asImageBitmap()
        } catch (_: RuntimeException) {
            null
        }
        cache.put(key, bitmap ?: Missing)
        return bitmap
    }
}

/**
 * The source app's launcher icon (loaded off the main thread), falling back to a lettered circle when the app is not
 * installed or not visible to UnsendVault. Decorative: the app name is always shown as text next to it.
 */
@Composable
fun AppAvatar(packageName: String, label: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState(AppIconCache.peek(packageName, sizePx), packageName, sizePx) {
        value = withContext(Dispatchers.IO) { AppIconCache.load(context.applicationContext, packageName, sizePx) }
    }
    val current = icon
    if (current != null) {
        Image(current, contentDescription = null, modifier = modifier.size(size).clip(CircleShape))
    } else {
        LetterAvatar(label, modifier, size)
    }
}

@Composable
fun LetterAvatar(label: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label.trim().firstOrNull()?.uppercase() ?: "?",
                style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}
