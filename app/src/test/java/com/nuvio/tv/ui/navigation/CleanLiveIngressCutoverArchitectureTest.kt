package com.nuvio.tv.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanLiveIngressCutoverArchitectureTest {
    private val source = navSource()

    @Test
    fun `one lifecycle fenced dispatch owns every prepared content-card live cutover`() {
        val dispatch = source.substringAfter("fun dispatchLiveOrElse(")
            .substringBefore("fun isStreamToPlayer(")

        assertTrue(dispatch.contains("XtreamItemRegistry.isLiveContentId(contentId)"))
        assertTrue(dispatch.contains("cleanLiveIngressJob.getAndSet(null)?.cancel()"))
        assertTrue(dispatch.contains("owner.lifecycleScope.launch"))
        assertTrue(dispatch.contains("Lifecycle.State.RESUMED"))
        assertTrue(dispatch.contains("navController.currentBackStackEntry === owner"))
        assertTrue(dispatch.contains("Screen.CleanLivePlayer.createRoute(result.token)"))
        assertTrue(dispatch.contains("is CleanLiveIngressResult.Rejected -> Unit"))
        assertFalse(dispatch.contains("Screen.Player.createRoute"))
        assertFalse(dispatch.contains("streamUrl"))
        assertFalse(dispatch.contains("recordPlayed"))
        assertFalse(dispatch.contains("resolve("))
    }

    @Test
    fun `search library folder and see-all each use their exact typed origin`() {
        listOf("SEARCH", "LIBRARY", "FOLDER", "CATALOG_SEE_ALL").forEach { origin ->
            assertEquals(
                "Expected one prepared $origin ingress",
                1,
                Regex("origin = CleanLiveLaunchOrigin\\.$origin,").findAll(source).count(),
            )
        }

        assertTrue(searchDetailCallback().contains("origin = CleanLiveLaunchOrigin.SEARCH"))
        assertTrue(libraryDetailCallback().contains("origin = CleanLiveLaunchOrigin.LIBRARY"))
        assertTrue(folderDetailCallback().contains("origin = CleanLiveLaunchOrigin.FOLDER"))
        assertTrue(catalogDetailCallback().contains("origin = CleanLiveLaunchOrigin.CATALOG_SEE_ALL"))
    }

    @Test
    fun `legacy live resolver and pre-play history write are absent from navigation`() {
        assertFalse(source.contains("XtreamLiveResolverViewModel"))
        assertFalse(source.contains("liveResolver.resolve"))
        assertFalse(source.contains("liveResolver.recordPlayed"))
    }

    @Test
    fun `non-live detail behavior remains local to each original callback`() {
        val search = searchDetailCallback()
        val library = libraryDetailCallback()
        val folder = folderDetailCallback()
        val catalog = catalogDetailCallback()

        listOf(search, library, folder, catalog).forEach { callback ->
            assertTrue(callback.contains("Screen.Detail.createRoute"))
            assertFalse(callback.contains("Screen.Player.createRoute"))
        }
        assertTrue(search.contains("HeroBackdropState.consumeAndClear()"))
        assertTrue(folder.contains("HeroBackdropState.consumeAndClear()"))
        assertFalse(library.contains("HeroBackdropState.consumeAndClear()"))
        assertFalse(catalog.contains("HeroBackdropState.consumeAndClear()"))
    }

    private fun searchDetailCallback(): String = source
        .substringAfter("composable(Screen.Search.route)")
        .substringAfter("onNavigateToDetail =")
        .substringBefore("onNavigateToSeeAll =")

    private fun libraryDetailCallback(): String = source
        .substringAfter("composable(Screen.Library.route)")
        .substringAfter("onNavigateToDetail =")
        .substringBefore("onCloudPlaybackResolved =")

    private fun folderDetailCallback(): String = source
        .substringAfter("route = Screen.FolderDetail.route")
        .substringAfter("onNavigateToDetail =")
        .substringBefore("onBack =")

    private fun catalogDetailCallback(): String = source
        .substringAfter("route = Screen.CatalogSeeAll.route")
        .substringAfter("onNavigateToDetail =")
        .substringBefore("onBackPress =")

    private fun navSource(): String {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(userDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV project root")
        return File(
            projectRoot,
            "app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt",
        ).readText()
    }
}
