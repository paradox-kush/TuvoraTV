package com.nuvio.tv.data.remote.supabase

import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import javax.inject.Inject
import javax.inject.Singleton

data class AvatarCatalogItem(
    val id: String,
    val displayName: String,
    val imageUrl: String,
    val category: String,
    val sortOrder: Int,
    val bgColor: String? = null
)

/**
 * Fork: resolves both the Postgrest client and the avatar base URL live through
 * [SyncBackendSupabaseProvider] rather than holding @Singleton snapshots or reading BuildConfig.
 *
 * The base URL has to come from the selected backend because it differs per backend, and
 * [com.nuvio.tv.core.network.SyncBackendDefaults] already derives it from that backend's Supabase
 * URL when AVATAR_PUBLIC_BASE_URL is unset. Reading BuildConfig.AVATAR_PUBLIC_BASE_URL directly
 * bypassed that derivation, and since the property is unset it yielded an empty base — so
 * avatarImageUrl() returned a bare storage_path, which Coil cannot load. Result: no preset avatar
 * ever rendered on TV, even with a populated catalog.
 *
 * Resolving live also makes SyncBackendSwitchService's invalidateCache() actually sufficient: the
 * refetch after a switch now hits the new backend and builds URLs against it.
 */
@Singleton
class AvatarRepository @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
) {
    private var cachedCatalog: List<AvatarCatalogItem>? = null

    suspend fun getAvatarCatalog(): List<AvatarCatalogItem> {
        cachedCatalog?.let { return it }

        // Read once so every URL in this batch is built against the backend we actually queried,
        // even if a switch lands mid-fetch.
        val backend = supabaseProvider.selectedBackend

        val response = supabaseProvider.postgrest.rpc("get_avatar_catalog")
        val remote = response.decodeList<SupabaseAvatarCatalogItem>()
        val catalog = remote.map { item ->
            AvatarCatalogItem(
                id = item.id,
                displayName = item.displayName,
                imageUrl = backend.avatarStorageUrl(item.storagePath),
                category = item.category,
                sortOrder = item.sortOrder,
                bgColor = item.bgColor
            )
        }
        cachedCatalog = catalog
        return catalog
    }

    fun getAvatarImageUrl(avatarId: String, catalog: List<AvatarCatalogItem>): String? {
        return catalog.find { it.id == avatarId }?.imageUrl
    }

    fun invalidateCache() {
        cachedCatalog = null
    }
}
