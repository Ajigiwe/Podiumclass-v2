package online.podiumclass.app.ui.components

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user has asked Android to take the animation out of the UI.
 *
 * This is Android's "Remove animations" switch (Settings → Accessibility →
 * Remove animations, and the Developer options animation scales). It works by
 * zeroing [Settings.Global.ANIMATOR_DURATION_SCALE] —
 * [Settings.Global.TRANSITION_ANIMATION_SCALE] is checked as well because a few
 * OEM skins only touch that one.
 *
 * Compose already honours the scale for finite animations: a `tween` with a
 * scale of 0 snaps to its end value. What it does *not* handle gracefully on its
 * own is an infinite animation, which is why the screens that use one ask this
 * first and build a static frame instead. The important part is not "no
 * movement" but "the finished screen": everything that fades or slides in is
 * drawn at its final position, so the user sees the complete design from the
 * first frame rather than a blank screen waiting for an entrance that will never
 * run.
 *
 * The value is observed, not just read once, so toggling the setting while a
 * screen is open takes effect immediately.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val reduced = remember { mutableStateOf(animationsDisabled(context)) }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced.value = animationsDisabled(context)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
            false,
            observer
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return reduced.value
}

private fun animationsDisabled(context: Context): Boolean {
    val resolver = context.contentResolver
    // Reading these is allowed for normal apps, but a managed device can have
    // them restricted; failing open (i.e. animating) is the safer default.
    return runCatching {
        val animatorScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        val transitionScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f
        )
        animatorScale == 0f || transitionScale == 0f
    }.getOrDefault(false)
}

/**
 * A looping 0 → 1 value for ambient motion — drifting glow, breathing rings, a
 * travelling highlight — that costs nothing when the user has asked for no
 * animation.
 *
 * With [reducedMotion] the animator is never created at all: no transition, no
 * frame clock registration, no per-frame draw invalidation. The caller still
 * gets a [State] holding [frozenAt], so the drawing code stays a single
 * expression and reads identically in both modes. Pick a [frozenAt] that leaves
 * the frame looking *finished*, not mid-flight.
 */
@Composable
fun rememberLoopingFloat(
    reducedMotion: Boolean,
    durationMillis: Int,
    label: String,
    repeatMode: RepeatMode = RepeatMode.Reverse,
    easing: Easing = LinearEasing,
    frozenAt: Float = 0f
): State<Float> {
    if (reducedMotion) {
        return remember(frozenAt) { mutableStateOf(frozenAt) }
    }

    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = easing),
            repeatMode = repeatMode
        ),
        label = label
    )
}
