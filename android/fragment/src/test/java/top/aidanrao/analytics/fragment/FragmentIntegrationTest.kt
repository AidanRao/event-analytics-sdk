package top.aidanrao.analytics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import top.aidanrao.analytics.PageInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class FragmentIntegrationTest : AdapterTestBase() {
    class Container : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(requireContext()).apply { id = 201 }
    }
    class Leaf : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = View(requireContext())
    }
    @Test fun hiddenAncestorSuppressesLeafAndForegroundReturnsOnce() {
        initialize()
        val host = Robolectric.buildActivity(FragmentActivity::class.java).create().start()
        val activity = host.get()
        activity.setContentView(FrameLayout(activity).apply { id = 200 })
        val binding = AnalyticsFragments.bind(analytics, activity) { fragment ->
            if (fragment is Leaf) PageInfo("leaf") else null
        }
        val parent = Container()
        activity.supportFragmentManager.beginTransaction().add(200, parent).commitNow()
        parent.childFragmentManager.beginTransaction().add(201, Leaf()).commitNow()
        host.resume().visible(); idle()
        org.junit.Assert.assertTrue("parent is attached/visible", parent.isVisible)
        org.junit.Assert.assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, parent.childFragmentManager.fragments.single().lifecycle.currentState)
        org.junit.Assert.assertTrue("foreground", analytics.isInForeground())
        org.junit.Assert.assertTrue("leaf visible", parent.childFragmentManager.fragments.single().isVisible)
        assertEquals(listOf("leaf"), pages())
        activity.supportFragmentManager.beginTransaction().hide(parent).commitNow()
        binding.refreshVisibility(); idle()
        tracker("background"); tracker("foreground"); idle()
        assertEquals(listOf("leaf"), pages())
        activity.supportFragmentManager.beginTransaction().show(parent).commitNow()
        binding.refreshVisibility(); idle()
        assertEquals(listOf("leaf", "leaf"), pages())
        tracker("background"); tracker("foreground"); idle()
        assertEquals(listOf("leaf", "leaf", "leaf"), pages())
        binding.close(); host.pause().stop().destroy()
    }
}
