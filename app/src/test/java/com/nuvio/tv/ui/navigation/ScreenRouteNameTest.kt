package com.nuvio.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenRouteNameTest {

    @Test
    fun routePatternKeepsOnlyItsStaticHead() {
        assertEquals("player", ScreenRouteName.of("player/{id}?t=x", 1))
        assertEquals("home", ScreenRouteName.of("home", 1))
        assertEquals("watch_party", ScreenRouteName.of("watch_party?code={code}", 1))
        assertEquals("clean-live", ScreenRouteName.of("clean-live/{launchToken}", 1))
        assertEquals("detail", ScreenRouteName.of(Screen.Detail.route, 1))
        assertEquals("player", ScreenRouteName.of(Screen.Player.route, 1))
    }

    @Test
    fun concreteArgumentsNeverPassThrough() {
        assertEquals(
            "player",
            ScreenRouteName.of("player/https%3A%2F%2Fhost.example%2Fs.m3u8/My%20Movie?contentId=tt123", 1),
        )
        assertEquals("stream", ScreenRouteName.of("stream/tt123/movie/Title", 1))
    }

    @Test
    fun missingOrUnsafeRoutesFallBackToTheDestinationId() {
        assertEquals("dest_42", ScreenRouteName.of(null, 42))
        assertEquals("dest_42", ScreenRouteName.of("", 42))
        assertEquals("dest_42", ScreenRouteName.of("{id}/x", 42))
        assertEquals("dest_42", ScreenRouteName.of("https://host.example/x", 42))
    }

    @Test
    fun everyDeclaredScreenNamesItselfWithoutArguments() {
        val routes = listOf(
            Screen.Home, Screen.Detail, Screen.Stream, Screen.Player, Screen.CleanLivePlayer,
            Screen.Settings, Screen.CastDetail, Screen.CatalogSeeAll, Screen.FolderDetail,
        ).map { it.route }
        routes.forEach { route ->
            val name = ScreenRouteName.of(route, 0)
            assertEquals("no argument or query in $name", true, name.none { it in "{}?/=&" })
            assertEquals("named from the route head", route.substringBefore('?').substringBefore('/'), name)
        }
    }
}
