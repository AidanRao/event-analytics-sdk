package top.aidanrao.analytics.navigation
import org.junit.Assert.*
import org.junit.Test
class VisitStateTest {
 @Test fun tracksTransitionsAndForegroundWithoutDuplicateCallbacks() {
  val state = VisitState()
  val first = Any(); val second = Any()
  assertTrue(state.select(first))
  assertFalse(state.select(first))
  assertTrue(state.select(second))
  assertTrue(state.select(first))
  state.background()
  assertTrue(state.select(first))
  assertFalse(state.select(first))
 }
 @Test fun returningAfterAnIgnoredPageIsANewVisit() {
  val state = VisitState()
  val page = Any()
  assertTrue(state.select(page))
  state.clear()
  assertTrue(state.select(page))
 }
}
