package top.aidanrao.analytics.navigation

/** Deduplicates lifecycle callbacks, while keeping distinct page visits. */
internal class VisitState {
    private var current: Any? = null
    private var needsForegroundVisit = false
    fun select(key: Any): Boolean {
        if (current === key && !needsForegroundVisit) return false
        current = key
        needsForegroundVisit = false
        return true
    }
    fun clear() { current = null }
    fun background() { needsForegroundVisit = true }
}
