package io.github.carnager.jot.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/** Motion is quick (≤250 ms) and follows the system animator duration scale. */
object Motion {
    const val SHORT = 180
    const val MEDIUM = 240

    fun <T> fast() = tween<T>(SHORT)
    fun <T> medium() = tween<T>(MEDIUM)

    fun scale(context: Context): Float =
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

    /** A delay that lines up with an animation of [ms], scaled like the animation itself. */
    suspend fun wait(context: Context, ms: Int) {
        val s = scale(context)
        if (s > 0f) delay((ms * s).toLong())
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Card ↔ editor container transform. A no-op outside the shared transition. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedItem(id: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalAnimScope.current ?: return this
    return with(shared) {
        this@sharedItem.sharedBounds(
            rememberSharedContentState("item-$id"),
            animatedVisibilityScope = anim,
            boundsTransform = { _, _ -> tween(Motion.MEDIUM) },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}
