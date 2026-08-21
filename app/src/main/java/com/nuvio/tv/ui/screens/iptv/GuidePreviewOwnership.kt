package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamItemRegistry

/**
 * Does a live-guide preview belong to a given account?
 *
 * The guide's "minimized" preview is driven by a SINGLE persistent player shared across account
 * switches (`XtreamLiveGuideScreen`). When the user switches to a different playlist the preview
 * must be torn down and re-tuned to the new account — otherwise it keeps decoding the previous
 * playlist's channel ("switch playlist, the old live TV keeps playing", root-caused 2026-08-20).
 *
 * The auto-resume decision "is a preview already up for THIS account?" hangs on ownership, so it is
 * pulled out here as a pure predicate: the failure mode is invisible in a screenshot (the wrong
 * stream just keeps playing) and only a stated invariant keeps it fixed.
 *
 * Content ids are `xtream:<accountId>:live:<streamId>` and [accountId] itself may contain ':' or
 * '|' (it is `baseUrl|username`), so ownership is a prefix check via
 * [XtreamItemRegistry.accountPrefix] — never a split on ':'.
 */
object GuidePreviewOwnership {

    /** True when [previewContentId] is a content id belonging to [accountId]. Null → false. */
    fun belongsTo(previewContentId: String?, accountId: String): Boolean =
        previewContentId != null &&
            previewContentId.startsWith(XtreamItemRegistry.accountPrefix(accountId))
}
