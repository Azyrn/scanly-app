package com.skeler.scanely.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Sizes for the scan flow (camera, crop, review), from Material 3 tokens (the material3 1.5.0 `tokens` sources) or a rule
 * in docs/scan-flow-spec.md. scripts/verify_scan_ui.py checks the live layout against the same numbers.
 */
object ScanTokens {
    // Spacing: multiples of the 4 dp half-step (m3 space50).
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space6 = 24.dp
    val Space8 = 32.dp

    /** Material minimum touch target (InteractiveComponentSize). */
    val TouchTarget = 48.dp

    /** Small app bar height (AppBarSmallTokens). */
    val TopBarHeight = 64.dp

    /** Small app bar horizontal padding (AppBar.kt TopAppBarHorizontalPadding). */
    val TopBarPadding = 4.dp

    val IconSize = 24.dp

    /** Button S icon (ButtonSmallTokens). */
    val SmallButtonIconSize = 20.dp

    /** Icon button / button S container (SmallIconButtonTokens, ButtonSmallTokens). */
    val SmallControlHeight = 40.dp

    /** Icon button M / button M (MediumIconButtonTokens, ButtonMediumTokens). */
    val MediumControlSize = 56.dp

    /** Gallery import: a 52 dp glass circle (fill 10 %, 1 dp rim 18 %) with a 26 dp icon. */
    val GlassButtonSize = 52.dp
    val GlassRim = 1.dp
    val GlassIconSize = 26.dp

    /** Shutter = FAB medium (FabMediumTokens). */
    val ShutterSize = 80.dp
    val ShutterRing = 4.dp

    /** Inner disc inset: 8 dp gap inside the ring, so the disc is 64 dp. */
    val ShutterDiscInset = 8.dp

    /** Bottom bar = shutter + 16 above and below. */
    val BottomBarHeight = 112.dp

    /** Pill behind the selected mode and the guidance text. */
    val PillHeight = 32.dp

    val Stroke = 2.dp
    val HairStroke = 1.dp

    /** ID frame inset from the viewfinder sides. */
    val IdFrameInset = 32.dp

    /** Crop corner handle: visible circle; its touch target is [TouchTarget]. */
    val HandleSize = 24.dp

    /** Crop image area side padding: half the visible handle + 2, so the photo is as wide as it can be. */
    val CropPadSide = 14.dp

    /** Loupe = the 96 dp large-control size (FabLargeTokens). */
    val LoupeSize = 96.dp
    const val LOUPE_ZOOM = 3f

    /** The loupe grows from this scale as it fades in (spec 4, row 3c). */
    const val LOUPE_START_SCALE = 0.8f

    /** Elevation level 3 (ElevationTokens). */
    val LoupeElevation = 6.dp

    /** Light theme only: the viewfinder's soft, app-tinted shadow. */
    val ViewfinderElevationLight = 10.dp

    /** Badge large (BadgeTokens). */
    val BadgeSize = 16.dp

    /** Scrim opacity (ScrimTokens) for everything outside the quad or ID frame. */
    const val SCRIM_ALPHA = 0.32f

    /** Dragged state-layer opacity (StateTokens) for the quad fill. */
    const val QUAD_FILL_ALPHA = 0.16f

    /** Viewfinder aspect, width / height: the 3:4 sensor in portrait. */
    const val VIEWFINDER_ASPECT = 3f / 4f

    /** MotionTokens.DurationShort2: each half of the capture flash. */
    const val CAPTURE_FLASH_MILLIS = 100

    /** Capture flash peak: `surface` at 0.32 over the viewfinder (spec 3.2). */
    const val CAPTURE_FLASH_ALPHA = 0.32f

    /** ID frame corner radius as a share of its width: the ID-1 card's 3.18 mm on 85.60 mm (spec 3.2). */
    const val ID_FRAME_CORNER_RATIO = 3.18f / 85.60f

    /** Loupe centre sits this far above the finger: half the loupe plus one touch target (spec 4, 3c). */
    val LoupeLift = LoupeSize / 2 + TouchTarget
}
