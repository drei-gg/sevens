package gg.seven.qr

import gg.seven.qr.core.Analysis
import gg.seven.qr.core.MakeRequest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The screens pushed over Scan (iOS RootRoute and the pushes from Settings and History). */
sealed interface Route {
    data object History : Route

    data class HistoryDetail(val id: UUID) : Route

    data object Settings : Route

    data object NetworkContact : Route

    data object KnownAddresses : Route

    /** Make, optionally pre-filled from a result's "Make a code". */
    data class Make(val prefill: MakeRequest?) : Route
}

/**
 * Where the app is: the stack over Scan, the result sheet and the pick list. Held by the
 * ViewModel, so a rotation or a theme change keeps the place.
 */
class QrNav {
    private val mutableStack = MutableStateFlow<List<Route>>(emptyList())
    val stack: StateFlow<List<Route>> = mutableStack.asStateFlow()

    /** The result sheet over whatever screen opened it. */
    val result = MutableStateFlow<Analysis?>(null)
    val candidates = MutableStateFlow<List<ScanCandidate>?>(null)

    fun push(route: Route) {
        mutableStack.value = mutableStack.value + route
    }

    fun pop() {
        mutableStack.value = mutableStack.value.dropLast(1)
    }

    /** Back to [route] if it is on the stack (Done in Make, Delete in a detail). */
    fun popTo(predicate: (Route) -> Boolean) {
        val index = mutableStack.value.indexOfLast(predicate)
        if (index >= 0) mutableStack.value = mutableStack.value.take(index + 1)
    }

    fun showResult(analysis: Analysis) {
        result.value = analysis
    }
}
