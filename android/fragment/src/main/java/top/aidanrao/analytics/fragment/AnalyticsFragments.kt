package top.aidanrao.analytics.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.aidanrao.analytics.Analytics
import top.aidanrao.analytics.AutoTrackingListener
import top.aidanrao.analytics.PageInfo
import java.io.Closeable

/** Opt-in Fragment pages. Do not bind alongside Navigation for the same page container. */
object AnalyticsFragments {
    @JvmStatic
    fun bind(
        analytics: Analytics,
        activity: FragmentActivity,
        mapper: (Fragment) -> PageInfo?
    ): FragmentPageBinding {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Bind page tracking on the main thread" }
        return FragmentPageBinding(analytics, activity, mapper).also { it.start() }
    }
}

/** After show/hide or custom visibility changes, call refreshVisibility() after the transaction commits. */
class FragmentPageBinding internal constructor(
    private val analytics: Analytics,
    private val activity: FragmentActivity,
    private val mapper: (Fragment) -> PageInfo?
) : Closeable, AutoTrackingListener {
    private val handler = Handler(Looper.getMainLooper())
    private val visits = VisitState()
    private val manager = activity.supportFragmentManager
    private val suppression = analytics.suppressActivityPages(activity)
    private var subscription: Closeable? = null
    private var closed = false
    private var first = true
    private var foregroundReturn = false
    private val refresh = Runnable { report() }
    // A Fragment can resume before its window is attached. Re-evaluate when it actually becomes
    // visible, rather than permanently missing the first page after onResume's early callback.
    private val windowAttachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = refreshVisibility()
        override fun onViewDetachedFromWindow(view: View) {}
    }
    private val hostLifecycle = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) close()
    }
    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = refreshVisibility()
        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) = refreshVisibility()
        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) = refreshVisibility()
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) = refreshVisibility()
    }

    internal fun start() {
        subscription = analytics.addAutoTrackingListener(this)
        activity.lifecycle.addObserver(hostLifecycle)
        activity.window.decorView.addOnAttachStateChangeListener(windowAttachment)
        manager.registerFragmentLifecycleCallbacks(callbacks, true)
        refreshVisibility()
    }

    fun refreshVisibility() {
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    private fun currentPage(fm: FragmentManager): Pair<Fragment, PageInfo>? {
        val primary = fm.primaryNavigationFragment
        val candidates = fm.fragments.filter { it !== primary } + listOfNotNull(primary)
        for (fragment in candidates.asReversed()) {
            if (!fragment.isAdded || fragment.isHidden || !fragment.isVisible ||
                fragment.lifecycle.currentState != Lifecycle.State.RESUMED) continue
            currentPage(fragment.childFragmentManager)?.let { return it }
            val page = runCatching { mapper(fragment) }.getOrNull()
            if (page != null) return fragment to page
        }
        return null
    }

    private fun report() {
        if (closed || !analytics.isAutoTrackingEnabled() || !analytics.isInForeground()) return
        val selected = currentPage(manager)
        if (selected == null) { visits.clear(); return }
        val (fragment, page) = selected
        if (!visits.select(fragment)) return
        analytics.pageView(page.id, page.name, when {
            first -> "initial"
            foregroundReturn -> "foreground"
            else -> "fragment"
        })
        first = false
        foregroundReturn = false
    }

    override fun onBackground() {
        visits.background()
        foregroundReturn = true
    }
    override fun onForeground() = refreshVisibility()
    override fun onStopped() { close() }
    override fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { close() }
            return
        }
        if (closed) return
        closed = true
        handler.removeCallbacks(refresh)
        activity.window.decorView.removeOnAttachStateChangeListener(windowAttachment)
        manager.unregisterFragmentLifecycleCallbacks(callbacks)
        activity.lifecycle.removeObserver(hostLifecycle)
        subscription?.close()
        suppression.close()
    }
}
