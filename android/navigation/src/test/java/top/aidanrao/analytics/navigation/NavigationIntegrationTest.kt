package top.aidanrao.analytics.navigation

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavOptions
import androidx.navigation.Navigator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigationIntegrationTest : AdapterTestBase() {
    @Navigator.Name("test")
    class TestNavigator : Navigator<NavDestination>() {
        override fun createDestination() = NavDestination(this)
        override fun navigate(destination: NavDestination, args: Bundle?, navOptions: NavOptions?, navigatorExtras: Extras?) = destination
        override fun popBackStack() = true
    }
    private fun controller(activity: FragmentActivity, saved: Bundle? = null): NavController {
        val nav = NavController(activity)
        nav.setLifecycleOwner(activity)
        nav.setViewModelStore(activity.viewModelStore)
        val navigator = TestNavigator()
        nav.navigatorProvider.addNavigator(navigator)
        if (saved != null) nav.restoreState(saved)
        val graph = NavGraph(NavGraphNavigator(nav.navigatorProvider)).apply {
            id = 100
            addDestination(navigator.createDestination().apply { id = 1; route = "home" })
            addDestination(navigator.createDestination().apply { id = 2; route = "detail/{id}" })
            setStartDestination("home")
        }
        nav.graph = graph
        return nav
    }
    @Test fun navigationWaitsForResumeAndTracksPopButNotRepeatedCallbacks() {
        initialize()
        val host = Robolectric.buildActivity(FragmentActivity::class.java).create().start()
        val nav = controller(host.get())
        val binding = AnalyticsNavigation.bind(analytics, host.get(), nav)
        idle()
        assertEquals(emptyList<String>(), pages())
        host.resume().visible(); idle()
        assertEquals(listOf("home"), pages())
        nav.navigate("detail/123"); idle()
        nav.popBackStack(); idle()
        assertEquals(listOf("home", "detail/{id}", "home"), pages())
        idle()
        assertEquals(3, pages().size)
        tracker("background"); tracker("foreground"); idle()
        assertEquals(listOf("home", "detail/{id}", "home", "home"), pages())
        binding.close(); host.pause().stop().destroy()
    }
    @Test fun configurationRebindDoesNotInventAnotherVisit() {
        initialize()
        val old = Robolectric.buildActivity(FragmentActivity::class.java).create().start()
        val nav = controller(old.get())
        val binding = AnalyticsNavigation.bind(analytics, old.get(), nav)
        old.resume().visible(); idle()
        assertEquals(listOf("home"), pages())
        val saved = nav.saveState()
        binding.close()
        tracker("configurationChanged")
        old.pause().stop().destroy()
        val next = Robolectric.buildActivity(FragmentActivity::class.java).create().start()
        val rebound = AnalyticsNavigation.bind(analytics, next.get(), controller(next.get(), saved))
        next.resume().visible(); idle()
        assertEquals(listOf("home"), pages())
        rebound.close(); next.pause().stop().destroy()
    }
}
