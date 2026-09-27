package top.aidanrao.analytics.navigation

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import top.aidanrao.analytics.Analytics
import top.aidanrao.analytics.AutoTrackingListener
import top.aidanrao.analytics.PageInfo
import java.io.Closeable

/** Optional Navigation adapter. Bind once per NavController and close when its host leaves composition. */
object AnalyticsNavigation {
    @JvmStatic
    @JvmOverloads
    fun bind(
        analytics: Analytics,
        activity: Activity,
        navController: NavController,
        mapper: (NavDestination) -> PageInfo? = { destination ->
            PageInfo(destination.route ?: destination.id.toString())
        }
    ): Closeable {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Bind page tracking on the main thread" }
        val owner = activity as? LifecycleOwner
            ?: throw IllegalArgumentException("Navigation tracking requires a LifecycleOwner Activity")
        return Binding(analytics, activity, owner, navController, mapper).also { it.start() }
    }

    private class Binding(
        private val analytics: Analytics,
        activity: Activity,
        private val owner: LifecycleOwner,
        private val controller: NavController,
        private val mapper: (NavDestination) -> PageInfo?
    ) : Closeable, AutoTrackingListener {
        private val handler = Handler(Looper.getMainLooper())
        private val visits = VisitState()
        private val suppression = analytics.suppressActivityPages(activity)
        private var subscription: Closeable? = null
        private var entry: NavBackStackEntry? = null
        private var closed = false
        private var first = true
        private var foregroundReturn = false
        private val entryLifecycle = LifecycleEventObserver { _, _ -> report() }
        private val hostLifecycle = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) close()
        }
        private val updateEntry = Runnable {
            val next = controller.currentBackStackEntry
            if (entry !== next) {
                entry?.lifecycle?.removeObserver(entryLifecycle)
                entry = next
                next?.lifecycle?.addObserver(entryLifecycle)
            }
            report()
        }

        private val destinations = NavController.OnDestinationChangedListener { _, _, _ ->
            handler.removeCallbacks(updateEntry)
            handler.post(updateEntry)
        }

        fun start() {
            subscription = analytics.addAutoTrackingListener(this)
            owner.lifecycle.addObserver(hostLifecycle)
            controller.addOnDestinationChangedListener(destinations)
            updateEntry.run()
        }

        private fun report() {
            val current = entry ?: return
            if (controller.currentBackStackEntry !== current) return
            if (closed || !analytics.isAutoTrackingEnabled() || !analytics.isInForeground()) return
            if (current.lifecycle.currentState != Lifecycle.State.RESUMED) return
            val page = runCatching { mapper(current.destination) }.getOrNull()
            if (page == null) { visits.clear(); return }
            if (!visits.select(current)) return
            analytics.pageView(page.id, page.name, when {
                first -> "initial"
                foregroundReturn -> "foreground"
                else -> "navigation"
            })
            first = false
            foregroundReturn = false
        }

        override fun onBackground() {
            visits.background()
            foregroundReturn = true
        }
        override fun onForeground() { report() }
        override fun onStopped() { close() }
        override fun close() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post { close() }
                return
            }
            if (closed) return
            closed = true
            controller.removeOnDestinationChangedListener(destinations)
            handler.removeCallbacks(updateEntry)
            entry?.lifecycle?.removeObserver(entryLifecycle)
            entry = null
            owner.lifecycle.removeObserver(hostLifecycle)
            subscription?.close()
            suppression.close()
        }
    }
}
