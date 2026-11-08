package com.skeler.scanely.ui.scan

import com.skeler.scanely.core.scan.ScanMode

/**
 * Test tags of the scan screens, shown as resource-id in `uiautomator dump` (testTagsAsResourceId).
 * scripts/verify_scan_ui.py finds the spec's elements by these names; keep the two in step.
 */
object ScanTags {
    const val CAMERA_ROOT = "scan_camera_root"
    const val CAMERA_TOP_BAR = "scan_camera_topbar"
    const val CAMERA_CLOSE = "scan_camera_close"
    const val CAMERA_AUTO = "scan_camera_auto"
    const val CAMERA_FLASH = "scan_camera_flash"
    const val CAMERA_VIEWFINDER = "scan_camera_viewfinder"
    const val CAMERA_GUIDANCE = "scan_camera_guidance"
    const val CAMERA_QUAD = "scan_camera_quad"
    const val CAMERA_ID_FRAME = "scan_camera_idframe"
    const val CAMERA_KIND = "scan_camera_kind"
    const val CAMERA_KIND_CARD = "scan_camera_kind_card"
    const val CAMERA_KIND_PASSPORT = "scan_camera_kind_passport"
    const val CAMERA_MODES = "scan_camera_modes"
    const val CAMERA_MODE_PREFIX = "scan_camera_mode_"
    const val CAMERA_BOTTOM_BAR = "scan_camera_bottombar"
    const val CAMERA_GALLERY = "scan_camera_gallery"
    const val CAMERA_SHUTTER = "scan_camera_shutter"
    const val CAMERA_PAGES = "scan_camera_pages"
    const val CAMERA_PAGES_BADGE = "scan_camera_pages_badge"
    const val CAMERA_PERMISSION = "scan_camera_permission"

    const val CROP_ROOT = "scan_crop_root"
    const val CROP_TOP_BAR = "scan_crop_topbar"
    const val CROP_RETAKE = "scan_crop_retake"
    const val CROP_TITLE = "scan_crop_title"
    const val CROP_ROTATE = "scan_crop_rotate"
    const val CROP_IMAGE_AREA = "scan_crop_image"
    const val CROP_PHOTO = "scan_crop_photo"
    const val CROP_HANDLE_PREFIX = "scan_crop_handle_"
    const val CROP_LOUPE = "scan_crop_loupe"
    const val CROP_TOOLS = "scan_crop_tools"
    const val CROP_AUTO = "scan_crop_auto"
    const val CROP_FULL = "scan_crop_full"
    const val CROP_ACTIONS = "scan_crop_actions"
    const val CROP_SECONDARY = "scan_crop_secondary"
    const val CROP_PRIMARY = "scan_crop_primary"

    fun mode(mode: ScanMode): String = CAMERA_MODE_PREFIX + when (mode) {
        ScanMode.DOCUMENT -> "document"
        ScanMode.ID_CARD -> "id"
        ScanMode.WHITEBOARD -> "whiteboard"
    }

    /** tl, tr, br, bl in [NormQuad] corner order. */
    fun handle(corner: Int): String = CROP_HANDLE_PREFIX + listOf("tl", "tr", "br", "bl")[corner]
}
