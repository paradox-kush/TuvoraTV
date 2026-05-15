package com.nuvio.tv.domain.model

import com.nuvio.tv.core.debrid.DebridStreamFormatterDefaults

data class DebridSettings(
    val enabled: Boolean = false,
    val torboxApiKey: String = "",
    val realDebridApiKey: String = "",
    val streamNameTemplate: String = DebridStreamFormatterDefaults.NAME_TEMPLATE,
    val streamDescriptionTemplate: String = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE
)
