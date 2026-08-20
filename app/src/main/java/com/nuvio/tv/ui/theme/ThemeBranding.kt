package com.nuvio.tv.ui.theme

import androidx.annotation.DrawableRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppTheme

// Tuvora keeps a single brand wordmark across every theme. Upstream ships per-theme Nuvio-branded
// wordmarks; we deliberately pin to the fork's Tuvora wordmark so no Nuvio branding ever renders
// (the theme system still recolours everything else). The per-theme Nuvio variants were removed.
@get:DrawableRes
val AppTheme.brandWordmarkResource: Int
    get() = R.drawable.app_logo_wordmark
