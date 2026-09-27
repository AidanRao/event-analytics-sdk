# Fragment page tracking

Add `top.aidanrao:event-analytics-fragment` at the same version as the core SDK.

```kotlin
val binding = AnalyticsFragments.bind(analytics, activity) { fragment ->
    when (fragment) {
        is CourseFragment -> PageInfo("courses")
        is SettingsFragment -> PageInfo("settings")
        else -> null // opt in business pages; exclude containers
    }
}
```

Bind on the main thread after enabling automatic tracking. Only added, visible, non-hidden, RESUMED fragments with visible ancestors are considered. A mapped child takes precedence over its container. If multiple sibling pages qualify, the primary navigation fragment takes precedence, otherwise the last eligible fragment in the FragmentManager list wins. Use explicit mapping and primary-navigation selection for custom multi-pane layouts.

After a show/hide transaction or custom visibility change, call `binding.refreshVisibility()` after the transaction commits: show/hide alone does not reliably change Fragment lifecycle. ViewPager2 must keep off-screen pages below RESUMED; older custom pagers require correct visibility/maximum lifecycle configuration. The binding suppresses host Activity pages and closes on Activity destruction or automatic tracking stop; rebind after re-enabling. Call close() when removing a container earlier. Do not combine with the Navigation adapter for the same container.

Bind during Activity.onCreate, before its first resume. For Compose or any deferred binding, configure AutoTrackingOptions.activityPageMapper to return null for the navigation/fragment host in advance. Suppression prevents future Activity events; it cannot retract an Activity event already emitted before binding.
