package top.aidanrao.analytics.example

import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import top.aidanrao.analytics.Analytics
import top.aidanrao.analytics.PageInfo
import top.aidanrao.analytics.navigation.AnalyticsNavigation
import top.aidanrao.analytics.fragment.AnalyticsFragments

/** Compile-tested entry points for apps with Navigation or explicitly tagged Fragment pages.
 * Select ONE adapter for a page container. Close the returned binding when removing that container.
 */
object PageAdapters {
    fun navigation(sdk: Analytics, activity: FragmentActivity, controller: NavController) =
        AnalyticsNavigation.bind(sdk, activity, controller) { destination -> destination.route?.let { PageInfo(it) } }
    fun fragments(sdk: Analytics, activity: FragmentActivity) =
        AnalyticsFragments.bind(sdk, activity) { fragment -> fragment.tag?.let { PageInfo(it) } }
}
