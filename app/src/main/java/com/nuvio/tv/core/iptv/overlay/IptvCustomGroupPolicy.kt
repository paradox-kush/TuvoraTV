package com.nuvio.tv.core.iptv.overlay

/**
 * Custom groups (F02): user-made rows of channels, created on the website and synced to every device.
 * They were stored on devices but never shown. Pure.
 *
 * A group belongs to one playlist, or spans playlists (null). It lists member entity ids in the
 * viewer's order; a member is shown when this playlist's lineup has it and it is not hidden.
 *
 * TV twin of NuvioMobile's `features/iptv/overlay/IptvCustomGroupPolicy`.
 */
object IptvCustomGroupPolicy {

    private const val ROW_PREFIX = "grp:"

    /** The row id a group is listed under, distinct from any provider category id. */
    fun rowId(groupId: String): String = ROW_PREFIX + groupId

    /** The group behind a row id, or null for a provider category. */
    fun groupIdOf(rowId: String): String? = if (rowId.startsWith(ROW_PREFIX)) rowId.removePrefix(ROW_PREFIX) else null

    /** Groups to list for [playlistId]'s [contentType] rows: its own, plus those spanning playlists. */
    fun groupsFor(playlistId: String, contentType: String, groups: List<CustomGroup>): List<CustomGroup> =
        groups.filter { it.contentType == contentType && (it.playlistId == null || it.playlistId == playlistId) }

    /** The group's channels from [byEntity] (the lineup keyed by entity id), in the group's order. */
    fun <T> members(group: CustomGroup, byEntity: Map<String, T>, channelOverlay: Map<String, ChannelOverlay>): List<T> =
        group.memberEntityIds
            .distinct()
            .filter { channelOverlay[it]?.hidden != true }
            .mapNotNull { byEntity[it] }
}
