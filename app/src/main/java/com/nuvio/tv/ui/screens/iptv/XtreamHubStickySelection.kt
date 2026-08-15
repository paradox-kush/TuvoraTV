package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamAccount

/**
 * Fix 1 (sticky provider): which account the hub lands on.
 *
 * The in-session choice wins; a fresh entry (current == null) restores the remembered provider —
 * but only while that account still exists AND is enabled. Otherwise the first ENABLED account
 * (the pre-fix behavior): a remembered id whose playlist was deleted or toggled off must fall
 * back, never resurrect. Content-type clamping (a section the account has disabled) stays the
 * caller's job — this only decides WHICH account.
 */
internal fun resolveStickyAccount(
    current: String?,
    remembered: String?,
    accounts: List<XtreamAccount>,
): String? {
    val enabled = accounts.filter { it.enabled }
    return (current ?: remembered)?.takeIf { id -> enabled.any { it.id == id } }
        ?: enabled.firstOrNull()?.id
}

/**
 * The remembered section tab (stored by name), tolerating junk or names written by other builds —
 * anything unrecognized reads as "nothing remembered" and yields [fallback], never a throw.
 */
internal fun resolveStickySection(remembered: String?, fallback: XtreamSection): XtreamSection =
    XtreamSection.entries.firstOrNull { it.name == remembered } ?: fallback
