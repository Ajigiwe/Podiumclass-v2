package online.podiumclass.app.ui.screens.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import online.podiumclass.app.ui.components.PodiumMark
import online.podiumclass.app.ui.components.rememberLoopingFloat
import online.podiumclass.app.ui.components.rememberReducedMotion
import online.podiumclass.app.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.sin

/**
 * Brand splash: the first thing anyone sees, so it is the one screen allowed to
 * be a little theatrical.
 *
 * Motion is built from three cheap pieces rather than a stack of independent
 * animations:
 *
 *  - **One entrance timeline.** A single [Animatable] runs 0 → 1 over a second
 *    and every element reads its own slice of it (`ramp`), which is what gives
 *    the mark → wordmark → tagline → loader stagger its exact ordering. One
 *    animator, no drift between elements.
 *  - **A spring for the mark only.** It is the thing that should feel physical,
 *    so it overshoots slightly and unrotates as it settles.
 *  - **An idle clock.** Ambient glow drift, the breathing rings around the mark
 *    and the loading sweep all ride one infinite transition (see
 *    `rememberLoopingFloat`), which is skipped entirely under reduce-motion.
 *
 * Every animated value is read inside a `graphicsLayer` block or a `Canvas`
 * draw lambda, i.e. in the draw phase. Reading them there means a frame costs a
 * redraw of a handful of layers instead of a recomposition of the whole screen,
 * which is why the animation stays smooth on the modest phones this app targets.
 *
 * With Android's "Remove animations" setting on, the entrance completes in one
 * step and the idle clock is never created ([rememberReducedMotion]), so the
 * first frame is the finished screen: mark, wordmark, tagline and loader all at
 * full opacity. The hold before navigating also shortens, because a static logo
 * is not doing the work the animated one was.
 */
@Composable
fun SplashScreen(
    onCheckAuth: suspend () -> Boolean,
    onNavigate: (hasToken: Boolean) -> Unit
) {
    val accent = Color(0xFF1845D4)
    val reducedMotion = rememberReducedMotion()

    val enter = remember { Animatable(0f) }
    val markSpring = remember { Animatable(0f) }

    // Frozen at 0 the glow sits at its base position and one ring is left
    // standing at 34% behind the mark -- a finished frame rather than an
    // interrupted animation.
    val drift = rememberLoopingFloat(
        reducedMotion = reducedMotion,
        durationMillis = 9000,
        label = "SplashDrift"
    )
    val pulse = rememberLoopingFloat(
        reducedMotion = reducedMotion,
        durationMillis = 2600,
        label = "SplashPulse",
        repeatMode = RepeatMode.Restart
    )
    val sweep = rememberLoopingFloat(
        reducedMotion = reducedMotion,
        durationMillis = 1400,
        label = "SplashSweep",
        easing = FastOutSlowInEasing
    )

    // 0 at `from`, 1 once the timeline has run `span` past it. Called from draw
    // lambdas, so the read stays out of composition.
    fun ramp(from: Float, span: Float = 0.34f): Float =
        ((enter.value - from) / span).coerceIn(0f, 1f)

    LaunchedEffect(Unit) {
        if (reducedMotion) {
            // Jump the whole timeline to its end: no entrance to wait for, so
            // nothing is invisible on the first frame.
            enter.snapTo(1f)
            markSpring.snapTo(1f)
        } else {
            launch { enter.animateTo(1f, tween(durationMillis = 1000, easing = FastOutSlowInEasing)) }
            launch {
                markSpring.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        // Long enough for the entrance to finish before the app moves on, so the
        // splash is branding rather than a flash of colour. Keyed on Unit (not
        // on the motion setting) so a settings change mid-splash cannot fire
        // this navigation twice.
        delay(if (reducedMotion) 420L else 1050L)
        onNavigate(onCheckAuth())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080F))
    ) {
        // ---- ambient light -------------------------------------------------
        Canvas(modifier = Modifier.fillMaxSize()) {
            val swing = sin(drift.value.toDouble() * PI).toFloat()

            val washRadius = size.minDimension * 0.95f
            val washCentre = Offset(
                size.width * (0.5f + 0.07f * swing),
                size.height * (0.33f + 0.02f * swing)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.30f), Color.Transparent),
                    center = washCentre,
                    radius = washRadius
                ),
                radius = washRadius,
                center = washCentre
            )

            val floorRadius = size.minDimension * 0.85f
            val floorCentre = Offset(
                size.width * (0.16f + 0.05f * swing),
                size.height
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                    center = floorCentre,
                    radius = floorRadius
                ),
                radius = floorRadius,
                center = floorCentre
            )
        }

        // ---- brand ---------------------------------------------------------
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(148.dp)
            ) {
                // Two rings half a cycle apart, expanding out of the mark.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val phaseBase = pulse.value
                    for (ring in 0..1) {
                        val phase = (phaseBase + ring * 0.5f) % 1f
                        val ringRadius = size.minDimension * (0.30f + 0.19f * phase)
                        // Fading in AND out over the cycle: the ring is born
                        // transparent at the mark's edge and dissolves as it
                        // travels, so the restart is invisible rather than a
                        // flash of a new circle.
                        val ringAlpha = 0.34f * sin(phase.toDouble() * PI).toFloat()
                        if (ringAlpha > 0.01f) {
                            drawCircle(
                                color = accent.copy(alpha = ringAlpha),
                                radius = ringRadius,
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }
                    val halo = size.minDimension * 0.46f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.26f + 0.06f * sin(pulse.value.toDouble() * 2.0 * PI).toFloat()),
                                Color.Transparent
                            ),
                            radius = halo
                        ),
                        radius = halo
                    )
                }

                Box(
                    modifier = Modifier.graphicsLayer {
                        val s = markSpring.value
                        val scale = 0.62f + 0.38f * s
                        scaleX = scale
                        scaleY = scale
                        // Unrotates as it lands: the mark arrives, it does not
                        // just appear.
                        rotationZ = (1f - s) * -12f
                        alpha = (s * 2f).coerceIn(0f, 1f)
                    }
                ) {
                    PodiumMark(
                        size = 84.dp,
                        containerColor = accent,
                        cornerRadius = 22.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Podium",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                modifier = Modifier.graphicsLayer {
                    val p = ramp(0.14f)
                    alpha = p
                    translationY = (1f - p) * 16.dp.toPx()
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Virtual Classroom & Presence Verification",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    val p = ramp(0.30f)
                    alpha = p
                    translationY = (1f - p) * 12.dp.toPx()
                }
            )

            Spacer(modifier = Modifier.height(44.dp))

            // Branded loader: a thin rail with a light travelling along it,
            // which reads as "working" without the generic spinner.
            Box(
                modifier = Modifier
                    .width(132.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .graphicsLayer { alpha = ramp(0.46f) }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (reducedMotion) {
                        // A travelling highlight is motion; with it suppressed
                        // the rail becomes a steady accent rule, which still
                        // says "loading" without pretending to be progress.
                        drawRoundRect(
                            color = accent.copy(alpha = 0.45f),
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = CornerRadius(size.height / 2f)
                        )
                    } else {
                        val segment = size.width * 0.42f
                        val startX = -segment + (size.width + segment) * sweep.value
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    accent.copy(alpha = 0.85f),
                                    Color.White,
                                    accent.copy(alpha = 0.85f),
                                    Color.Transparent
                                ),
                                startX = startX,
                                endX = startX + segment
                            ),
                            topLeft = Offset(startX, 0f),
                            size = Size(segment, size.height),
                            cornerRadius = CornerRadius(size.height / 2f)
                        )
                    }
                }
            }
        }

        // ---- footer --------------------------------------------------------
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp)
                .graphicsLayer {
                    val p = ramp(0.55f)
                    alpha = p
                    translationY = (1f - p) * 10.dp.toPx()
                }
        ) {
            Text(
                text = "Podium Cloud • Institutional Workspace",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
