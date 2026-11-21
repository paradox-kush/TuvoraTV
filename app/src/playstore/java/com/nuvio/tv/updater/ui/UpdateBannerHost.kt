package com.nuvio.tv.updater.ui

import androidx.compose.runtime.Composable
import com.nuvio.tv.updater.UpdateViewModel

@Composable
fun UpdateBannerHost(
    viewModel: UpdateViewModel,
    playerRouteActive: Boolean,
    content: @Composable () -> Unit
) {
    content()
}
