# Navigation page tracking

[中文最小接入示例（含 ClassHopper）](../quickstart/compose-navigation.md)

Declare only `implementation("top.aidanrao:event-analytics-navigation:0.2.0")`. It transitively includes the core SDK; initialize one Analytics instance. Do not add the Fragment adapter to the same navigation container.
Works with AndroidX Navigation (including Navigation Compose); the adapter does not depend on Compose.

```kotlin
val binding = AnalyticsNavigation.bind(analytics, activity, navController) { destination ->
    destination.route?.let { PageInfo(it) } // route template, never resolved route arguments
}
// For Compose, create inside DisposableEffect(analytics, activity, navController),
// and call binding.close() from onDispose.
```

Bind on the main thread after enabling automatic tracking. Each bound controller should represent a single business page container. The default mapper uses the route template or destination resource ID; supply explicit stable IDs for XML navigation and return null for excluded destinations. Mappers must not expose route argument values, user data, or dynamic URLs. Tracking waits until the current entry is RESUMED, deduplicates lifecycle callbacks, and counts returning to an existing back-stack entry as another visit. Foreground returns also count once. The binding suppresses host Activity pages and closes on Activity destruction or automatic tracking stop; rebind after re-enabling. Do not also bind Fragment tracking to this container.

Bind during Activity.onCreate, before its first resume. For Compose or any deferred binding, configure AutoTrackingOptions.activityPageMapper to return null for the navigation/fragment host in advance. Suppression prevents future Activity events; it cannot retract an Activity event already emitted before binding.
