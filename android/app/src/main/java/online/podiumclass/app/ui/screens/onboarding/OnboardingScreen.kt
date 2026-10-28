package online.podiumclass.app.ui.screens.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush as GraphicsBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.launch
import online.podiumclass.app.ui.components.PodiumMark
import online.podiumclass.app.ui.components.rememberLoopingFloat
import online.podiumclass.app.ui.components.rememberReducedMotion

/**
 * First-run tour.
 *
 * Five short pages, one idea each: the room, presence, joining, hosting and the
 * admin panel. The illustrations are drawn with Canvas rather than shipped as
 * images -- they scale to any density, they recolour with the page accent, and
 * they cost nothing to download.
 *
 * Motion has three layers, in order of how loudly they speak:
 *
 *  - **The swipe.** Pages parallax, shrink very slightly and turn a shallow 9°
 *    on Y as they cross the centre, so dragging feels like handling cards rather
 *    than paging a list.
 *  - **The arrival.** Each page's artwork, eyebrow, headline, body and bullets
 *    fade up on their own beat. The stagger is what makes it feel scripted
 *    instead of everything swinging in at once.
 *  - **The idle.** One infinite clock drives the background glow and a 3dp bob
 *    under the artwork, so a page you are reading is never a still image.
 *
 * All three read their values inside `graphicsLayer` blocks or Canvas draw
 * lambdas -- draw-phase reads, so an animating frame redraws layers instead of
 * recomposing the page. That keeps the tour cheap on low-end hardware.
 *
 * With Android's "Remove animations" setting on ([rememberReducedMotion]) the
 * tour keeps its shape but loses its clocks: no idle transition is created, the
 * reveals and the accent blend snap, and the card turn is dropped. The page
 * still pages, because following your finger is direct manipulation rather than
 * decoration -- only the depth effect on top of it goes.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit
) {
    val pages = remember { onboardingPages() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val isLastPage = pagerState.currentPage == pages.lastIndex

    val reducedMotion = rememberReducedMotion()

    // The chrome (header, dots, button) arrives once, when the tour opens.
    val entered = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reducedMotion) {
            entered.snapTo(1f)
        } else {
            entered.animateTo(1f, tween(durationMillis = 640, easing = FastOutSlowInEasing))
        }
    }

    // A single idle clock for the whole screen: glow drift and the artwork bob
    // ride it together, so the movement is in step and there is one animator
    // instead of one per element. Not created at all under reduce-motion.
    val drift = rememberLoopingFloat(
        reducedMotion = reducedMotion,
        durationMillis = 11000,
        label = "OnboardingDrift"
    )

    // Blend between page accents rather than snapping, so the glow, the dots and
    // the button all ease across the colour change together.
    val accent by animateColorAsState(
        targetValue = pages[pagerState.currentPage].accent,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            tween(durationMillis = 460, easing = FastOutSlowInEasing)
        },
        label = "OnboardingAccent"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070910))
    ) {
        // ---- ambient glow --------------------------------------------------
        Canvas(modifier = Modifier.fillMaxSize()) {
            val swing = sin(drift.value.toDouble() * PI).toFloat()

            // Accent wash behind the artwork: it breathes with the accent, so
            // the whole screen changes colour as you page, not just the card.
            val washRadius = size.minDimension * 0.85f
            val washCentre = androidx.compose.ui.geometry.Offset(
                size.width * (0.5f + 0.05f * swing),
                size.height * (0.22f + 0.03f * swing)
            )
            drawCircle(
                brush = GraphicsBrush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.32f + 0.06f * swing),
                        Color.Transparent
                    ),
                    center = washCentre,
                    radius = washRadius
                ),
                radius = washRadius,
                center = washCentre
            )

            // Indigo floor, so the black never reads as unfinished.
            val floorRadius = size.minDimension * 0.8f
            val floorCentre = androidx.compose.ui.geometry.Offset(
                size.width * (0.15f + 0.05f * swing),
                size.height
            )
            drawCircle(
                brush = GraphicsBrush.radialGradient(
                    colors = listOf(Color(0xFF1845D4).copy(alpha = 0.22f), Color.Transparent),
                    center = floorCentre,
                    radius = floorRadius
                ),
                radius = floorRadius,
                center = floorCentre
            )

            // A faint counterweight on the right, drifting the other way, which
            // stops the composition leaning entirely to one corner.
            val orbRadius = size.minDimension * 0.5f
            val orbCentre = androidx.compose.ui.geometry.Offset(
                size.width * (0.9f - 0.06f * swing),
                size.height * (0.74f + 0.04f * swing)
            )
            drawCircle(
                brush = GraphicsBrush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.12f), Color.Transparent),
                    center = orbCentre,
                    radius = orbRadius
                ),
                radius = orbRadius,
                center = orbCentre
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // ---- header ----------------------------------------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 12.dp, top = 26.dp)
                    .graphicsLayer {
                        val p = entered.value
                        alpha = p
                        translationY = (1f - p) * -14.dp.toPx()
                    }
            ) {
                PodiumMark(size = 30.dp, containerColor = Color(0xFF1845D4), cornerRadius = 8.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Podium",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onFinished) {
                    Text("Skip", color = Color(0xFF94A3B8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ---- pages -----------------------------------------------------
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1
            ) { index ->
                val page = pages[index]
                // -1 .. 0 .. 1: how far this page is from being centred.
                val offset = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                val distance = abs(offset).coerceIn(0f, 1f)
                val active = pagerState.currentPage == index

                val artReveal = rememberReveal(active, reducedMotion, delayMillis = 20, durationMillis = 560)
                val eyebrowReveal = rememberReveal(active, reducedMotion, delayMillis = 150)
                val titleReveal = rememberReveal(active, reducedMotion, delayMillis = 230)
                val bodyReveal = rememberReveal(active, reducedMotion, delayMillis = 330)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 26.dp)
                        .graphicsLayer {
                            translationX = offset * 96.dp.toPx()
                            alpha = 1f - distance * 0.78f
                            if (!reducedMotion) {
                                val shrink = 1f - distance * 0.07f
                                scaleX = shrink
                                scaleY = shrink
                                // A shallow card-turn across the centre. 9° is enough
                                // to read as depth without becoming a slideshow.
                                rotationY = offset * 9f
                                cameraDistance = 16f * density
                            }
                        }
                ) {
                    Spacer(modifier = Modifier.height(6.dp))

                    PageIllustration(
                        art = page.art,
                        accent = page.accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.12f)
                            .graphicsLayer {
                                val p = artReveal.value
                                alpha = p
                                val s = 0.93f + 0.07f * p
                                scaleX = s
                                scaleY = s
                                // Idle bob, gentled by the reveal so the artwork
                                // settles into its float instead of starting in
                                // the middle of one.
                                val bob = sin(drift.value.toDouble() * PI).toFloat()
                                translationY = (1f - p) * 16.dp.toPx() + bob * 3.dp.toPx() * p
                            }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = page.eyebrow,
                        color = page.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.6.sp,
                        modifier = Modifier.revealUp(eyebrowReveal, distance = 14.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = page.title,
                        color = Color.White,
                        fontSize = 27.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.6).sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.revealUp(titleReveal, distance = 18.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = page.body,
                        color = Color(0xFF9AA2B8),
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.revealUp(bodyReveal, distance = 20.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    page.bullets.forEachIndexed { bulletIndex, bullet ->
                        // Staggered reveal: each bullet waits for the one above.
                        val bulletReveal = rememberReveal(
                            active = active,
                            reducedMotion = reducedMotion,
                            delayMillis = 430 + bulletIndex * 80,
                            durationMillis = 420
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp)
                                .revealUp(bulletReveal, distance = 16.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(page.accent.copy(alpha = 0.16f))
                            ) {
                                Icon(
                                    imageVector = bullet.first,
                                    contentDescription = null,
                                    tint = page.accent,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = bullet.second,
                                color = Color(0xFFCBD2E1),
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // ---- footer ----------------------------------------------------
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp)
                    .padding(bottom = 30.dp)
                    .graphicsLayer {
                        val p = ((entered.value - 0.2f) / 0.8f).coerceIn(0f, 1f)
                        alpha = p
                        translationY = (1f - p) * 22.dp.toPx()
                    }
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pages.indices.forEach { dotIndex ->
                        val selected = pagerState.currentPage == dotIndex
                        // Selected dot stretches into a pill; the rest stay
                        // small circles, so position AND progress are readable.
                        val width by animateFloatAsState(
                            targetValue = if (selected) 22f else 7f,
                            animationSpec = if (reducedMotion) {
                                snap()
                            } else {
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            },
                            label = "dot-$dotIndex"
                        )
                        Box(
                            modifier = Modifier
                                .height(7.dp)
                                .width(width.dp)
                                .clip(CircleShape)
                                .background(if (selected) accent else Color(0xFF2A3145))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                // Press feedback survives reduce-motion -- a button that does
                // not acknowledge a touch is a worse problem than a 3% scale --
                // but it becomes instant instead of springing.
                val pressScale by animateFloatAsState(
                    targetValue = if (pressed) 0.972f else 1f,
                    animationSpec = if (reducedMotion) {
                        snap()
                    } else {
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
                    },
                    label = "cta-press"
                )

                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isLastPage) {
                            onFinished()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(14.dp),
                    interactionSource = interaction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        }
                ) {
                    Text(
                        text = if (isLastPage) "Get started" else "Next",
                        color = Color(0xFF0A0D16),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color(0xFF0A0D16),
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                // The arrow leans into the press: a 2dp nudge is
                                // enough to make the button feel mechanical.
                                translationX = (1f - pressScale) * 40.dp.toPx()
                            }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (isLastPage) {
                        "Sign in with your institution Google account next."
                    } else {
                        "Swipe to see what else Podium does."
                    },
                    color = Color(0xFF5D6478),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Motion helpers
// ---------------------------------------------------------------------------

/**
 * One element's share of the page reveal: 0 while the page is off-centre, 1
 * once it is the current page, eased in after [delayMillis].
 *
 * Returned as a [State] rather than read with `by` so callers can hand it to
 * [revealUp] and have the value read in the draw phase.
 */
@Composable
private fun rememberReveal(
    active: Boolean,
    reducedMotion: Boolean,
    delayMillis: Int = 0,
    durationMillis: Int = 460
): State<Float> = animateFloatAsState(
    // Under reduce-motion every page reads as settled, so its content is drawn
    // in place rather than waiting on an entrance that will not play.
    targetValue = if (active || reducedMotion) 1f else 0f,
    animationSpec = if (reducedMotion) {
        snap()
    } else {
        tween(
            durationMillis = durationMillis,
            delayMillis = delayMillis,
            easing = FastOutSlowInEasing
        )
    },
    label = "reveal-$delayMillis"
)

/** Fade up into place, driven by a reveal progress from [rememberReveal]. */
private fun Modifier.revealUp(progress: State<Float>, distance: Dp = 18.dp): Modifier =
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * distance.toPx()
    }

// ---------------------------------------------------------------------------
// Page model
// ---------------------------------------------------------------------------

private enum class Art { Live, Presence, Join, Host, Admin }

private data class Page(
    val eyebrow: String,
    val title: String,
    val body: String,
    val bullets: List<Pair<ImageVector, String>>,
    val accent: Color,
    val art: Art
)

private fun onboardingPages(): List<Page> = listOf(
    Page(
        eyebrow = "THE CLASSROOM",
        title = "Class time,\nnot commute time",
        body = "Video, audio, screen share and chat in one room — on the phone that is already in your hand.",
        bullets = listOf(
            Icons.Default.Videocam to "Camera and mic with host-approved moderation",
            Icons.Default.PanTool to "Raise a hand, then take the stage when invited",
            Icons.Default.PictureInPictureAlt to "Picture-in-picture keeps the lecture playing"
        ),
        accent = Color(0xFF60A5FA),
        art = Art.Live
    ),
    Page(
        eyebrow = "ATTENDANCE",
        title = "Presence that\nproves itself",
        body = "A random check can arrive at any moment. Answer in seconds and your attendance is recorded — properly, not presumed.",
        bullets = listOf(
            Icons.Default.FactCheck to "Answer presence checks with one tap",
            Icons.Default.TaskAlt to "Your record is written the moment you check in",
            Icons.Default.History to "History stays yours, even after a class is deleted"
        ),
        accent = Color(0xFF34D399),
        art = Art.Presence
    ),
    Page(
        eyebrow = "GETTING IN",
        title = "One code,\nor one link",
        body = "No timetable hunting. Type the meeting code, or tap the invite link — the app takes you to the right room.",
        bullets = listOf(
            Icons.Default.VpnKey to "Enter the POD-XXXXXX code from your lecturer",
            Icons.Default.Link to "Invite links open the class directly",
            Icons.Default.HourglassEmpty to "Wait in the lobby; you enter when the host does"
        ),
        accent = Color(0xFFFBBF24),
        art = Art.Join
    ),
    Page(
        eyebrow = "FOR HOSTS",
        title = "Host it, or\njust show up",
        body = "Creating a class gives you a code and a link to share. It goes live when you walk in, and students follow.",
        bullets = listOf(
            Icons.Default.Add to "Create a class and share one link",
            Icons.Default.FiberManualRecord to "Record from the phone; watch and share from Records",
            Icons.Default.Brush to "Whiteboard while teaching in the web classroom"
        ),
        accent = Color(0xFFF472B6),
        art = Art.Host
    ),
    Page(
        eyebrow = "FOR ADMINS",
        title = "Run the\ninstitution",
        body = "The control panel travels with you: who is on the platform, what they may do, and what is switched on.",
        bullets = listOf(
            Icons.Default.ManageAccounts to "Promote lecturers, or grant class creation",
            Icons.Default.Block to "Disable an account and end its access",
            Icons.Default.AdminPanelSettings to "Live sessions, feature toggles and settings"
        ),
        accent = Color(0xFFA78BFA),
        art = Art.Admin
    )
)

// ---------------------------------------------------------------------------
// Illustrations
// ---------------------------------------------------------------------------

/**
 * Draws one page's illustration on a 220x180 grid, scaled to the space it is
 * given. Keeping every shape on that fixed grid means one set of coordinates
 * works on a 5" phone and a tablet alike.
 */
@Composable
private fun PageIllustration(art: Art, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = min(size.width / 220f, size.height / 180f)
        val ox = (size.width - 220f * s) / 2f
        val oy = (size.height - 180f * s) / 2f
        fun x(v: Float) = ox + v * s
        fun y(v: Float) = oy + v * s
        fun rect(left: Float, top: Float, w: Float, h: Float, color: Color, r: Float = 8f) {
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x(left), y(top)),
                size = androidx.compose.ui.geometry.Size(w * s, h * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * s, r * s)
            )
        }
        fun dot(cx: Float, cy: Float, radius: Float, color: Color) {
            drawCircle(color = color, radius = radius * s, center = androidx.compose.ui.geometry.Offset(x(cx), y(cy)))
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, w: Float = 2f) {
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(x(x1), y(y1)),
                end = androidx.compose.ui.geometry.Offset(x(x2), y(y2)),
                strokeWidth = w * s,
                cap = StrokeCap.Round
            )
        }
        fun person(cx: Float, cy: Float, color: Color) {
            dot(cx, cy - 4f, 5f, color)
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x(cx - 8f), y(cy + 3f)),
                size = androidx.compose.ui.geometry.Size(16f * s, 11f * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f * s, 6f * s)
            )
        }

        when (art) {
            Art.Live -> {
                rect(18f, 26f, 184f, 116f, Color(0xFF101528), r = 14f)
                rect(24f, 32f, 84f, 52f, accent.copy(alpha = 0.22f), r = 8f)
                rect(112f, 32f, 84f, 52f, Color.White.copy(alpha = 0.06f), r = 8f)
                rect(24f, 88f, 84f, 46f, Color.White.copy(alpha = 0.06f), r = 8f)
                rect(112f, 88f, 84f, 46f, Color.White.copy(alpha = 0.06f), r = 8f)
                person(66f, 56f, accent)
                person(154f, 60f, Color(0xFF64748B))
                person(66f, 112f, Color(0xFF64748B))
                person(154f, 112f, Color(0xFF64748B))
                // LIVE badge
                rect(34f, 40f, 40f, 13f, Color(0xFFEF4444), r = 6f)
                dot(40f, 46.5f, 3f, Color.White)
                line(46f, 46.5f, 48f, 46.5f, Color.White, 1.5f)
                line(51f, 46.5f, 68f, 46.5f, Color.White.copy(alpha = 0.5f), 2f)
                // control bar
                rect(74f, 146f, 72f, 20f, Color(0xFF171D31), r = 10f)
                dot(88f, 156f, 5f, Color.White.copy(alpha = 0.7f))
                dot(110f, 156f, 5f, Color.White)
                dot(132f, 156f, 5f, Color.White.copy(alpha = 0.7f))
            }

            Art.Presence -> {
                rect(22f, 30f, 150f, 112f, Color(0xFF101528), r = 14f)
                // three roster rows
                for (i in 0..2) {
                    val top = 46f + i * 30f
                    dot(46f, top + 10f, 8f, Color.White.copy(alpha = 0.14f))
                    line(60f, top + 6f, 118f, top + 6f, Color.White.copy(alpha = 0.22f), 4f)
                    line(60f, top + 15f, 96f, top + 15f, Color.White.copy(alpha = 0.12f), 3f)
                    if (i == 0) {
                        dot(146f, top + 10f, 8f, accent.copy(alpha = 0.9f))
                        line(142f, top + 10f, 145f, top + 13f, Color(0xFF0A0D16), 2f)
                        line(145f, top + 13f, 150f, top + 6f, Color(0xFF0A0D16), 2f)
                    } else {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.12f),
                            topLeft = androidx.compose.ui.geometry.Offset(x(138f), y(top + 2f)),
                            size = androidx.compose.ui.geometry.Size(16f * s, 16f * s),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * s, 5f * s),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f * s)
                        )
                    }
                }
                // big badge
                dot(184f, 136f, 26f, accent.copy(alpha = 0.16f))
                dot(184f, 136f, 18f, accent)
                line(176f, 136f, 182f, 142f, Color(0xFF0A0D16), 3f)
                line(182f, 142f, 192f, 129f, Color(0xFF0A0D16), 3f)
            }

            Art.Join -> {
                rect(20f, 34f, 180f, 46f, Color(0xFF101528), r = 12f)
                // code blocks
                for (i in 0..4) {
                    rect(32f + i * 26f, 50f, 18f, 14f, Color.White.copy(alpha = 0.16f), r = 4f)
                }
                rect(164f, 48f, 26f, 18f, accent, r = 5f)
                line(170f, 57f, 181f, 57f, Color(0xFF0A0D16), 2.5f)
                line(176f, 52f, 181f, 57f, Color(0xFF0A0D16), 2.5f)
                line(176f, 62f, 181f, 57f, Color(0xFF0A0D16), 2.5f)

                // link pill
                rect(20f, 92f, 128f, 26f, Color.White.copy(alpha = 0.06f), r = 13f)
                dot(36f, 105f, 7f, accent.copy(alpha = 0.35f))
                line(32f, 105f, 40f, 105f, accent, 2f)
                line(46f, 105f, 100f, 105f, Color.White.copy(alpha = 0.2f), 4f)
                line(106f, 105f, 120f, 105f, Color.White.copy(alpha = 0.1f), 4f)

                // arrow into the room
                line(136f, 105f, 156f, 105f, accent, 2.5f)
                line(150f, 99f, 157f, 105f, accent, 2.5f)
                line(150f, 111f, 157f, 105f, accent, 2.5f)

                // door + waiting student
                rect(162f, 78f, 38f, 66f, Color(0xFF101528), r = 10f)
                dot(192f, 111f, 2.5f, accent)
                person(174f, 132f, Color(0xFF94A3B8))
                line(30f, 152f, 190f, 152f, Color.White.copy(alpha = 0.08f), 2f)
            }

            Art.Host -> {
                // recording card
                rect(18f, 28f, 184f, 58f, Color(0xFF101528), r = 12f)
                dot(38f, 57f, 7f, Color(0xFFEF4444))
                line(52f, 57f, 60f, 57f, Color.White.copy(alpha = 0.4f), 3f)
                // waveform
                val heights = listOf(6f, 14f, 22f, 10f, 18f, 26f, 12f, 20f, 8f, 16f, 22f, 10f)
                heights.forEachIndexed { index, hgt ->
                    val cx = 76f + index * 10f
                    line(cx, 57f - hgt / 2f, cx, 57f + hgt / 2f, accent.copy(alpha = 0.85f), 3f)
                }

                // whiteboard below
                rect(18f, 96f, 128f, 64f, Color(0xFF0C1122), r = 12f)
                rect(18f, 96f, 128f, 64f, Color.White.copy(alpha = 0.05f), r = 12f)
                val stroke = Path().apply {
                    moveTo(x(32f), y(140f))
                    quadraticBezierTo(x(52f), y(104f), x(72f), y(132f))
                    quadraticBezierTo(x(92f), y(158f), x(112f), y(116f))
                }
                drawPath(
                    path = stroke,
                    color = accent,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f * s, cap = StrokeCap.Round)
                )
                dot(112f, 116f, 4f, Color.White)

                // pen
                rect(130f, 126f, 8f, 30f, Color.White.copy(alpha = 0.5f), r = 3f)
                rect(130f, 148f, 8f, 8f, accent, r = 3f)

                // share chip
                rect(154f, 108f, 48f, 24f, accent.copy(alpha = 0.16f), r = 12f)
                dot(166f, 120f, 4f, accent)
                line(178f, 120f, 194f, 120f, accent.copy(alpha = 0.6f), 3f)
            }

            Art.Admin -> {
                // panel
                rect(20f, 30f, 180f, 104f, Color(0xFF101528), r = 14f)
                rect(20f, 30f, 180f, 22f, Color.White.copy(alpha = 0.05f), r = 14f)
                dot(32f, 41f, 3f, Color(0xFFEF4444))
                dot(42f, 41f, 3f, Color(0xFFFBBF24))
                dot(52f, 41f, 3f, Color(0xFF34D399))

                // KPI tiles
                for (i in 0..2) {
                    val left = 32f + i * 54f
                    rect(left, 62f, 46f, 34f, Color.White.copy(alpha = 0.06f), r = 8f)
                    line(left + 8f, 74f, left + 26f, 74f, accent.copy(alpha = 0.8f), 4f)
                    line(left + 8f, 84f, left + 36f, 84f, Color.White.copy(alpha = 0.16f), 3f)
                }

                // user rows
                for (i in 0..1) {
                    val top = 104f + i * 20f
                    dot(38f, top + 7f, 5f, Color.White.copy(alpha = 0.16f))
                    line(48f, top + 7f, 108f, top + 7f, Color.White.copy(alpha = 0.18f), 3f)
                    rect(150f, top + 1f, 38f, 12f, if (i == 0) accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.1f), r = 6f)
                }

                // shield badge
                dot(186f, 148f, 24f, accent.copy(alpha = 0.14f))
                dot(186f, 148f, 16f, accent)
                line(180f, 148f, 185f, 153f, Color(0xFF0A0D16), 2.5f)
                line(185f, 153f, 193f, 141f, Color(0xFF0A0D16), 2.5f)
            }
        }
    }
}
