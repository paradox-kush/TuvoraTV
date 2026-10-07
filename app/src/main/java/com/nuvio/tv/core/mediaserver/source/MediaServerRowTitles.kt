package com.nuvio.tv.core.mediaserver.source

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow

/** The localized titles of the rows a server contributes - behind a seam so row building is testable without resources. */
internal interface MediaServerRowTitles {
    suspend fun home(row: MediaServerHomeRow, serverName: String): String
    suspend fun searchMovies(serverName: String): String
    suspend fun searchSeries(serverName: String): String
}

internal class ResourceMediaServerRowTitles(private val context: Context) : MediaServerRowTitles {
    override suspend fun home(row: MediaServerHomeRow, serverName: String): String = when (row) {
        MediaServerHomeRow.CONTINUE_WATCHING -> context.getString(R.string.media_server_row_continue_watching, serverName)
        MediaServerHomeRow.NEXT_UP -> context.getString(R.string.media_server_row_next_up, serverName)
        MediaServerHomeRow.RECENTLY_ADDED -> context.getString(R.string.media_server_row_recently_added, serverName)
    }

    override suspend fun searchMovies(serverName: String): String = context.getString(R.string.media_server_search_movies, serverName)
    override suspend fun searchSeries(serverName: String): String = context.getString(R.string.media_server_search_series, serverName)
}
