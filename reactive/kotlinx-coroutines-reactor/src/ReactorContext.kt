package kotlinx.coroutines.reactor

import reactor.util.context.Context
import kotlin.coroutines.*

/**
 * Marks coroutine context element that contains Reactor's [Context] elements in [context] for seamless integration
 * between [CoroutineContext] and Reactor's [Context].
 *
 * [Context.asCoroutineContext] is defined to add Reactor's [Context] elements as part of [CoroutineContext].
 *
 * Reactor builders: [mono], [flux] can extract the reactor context from their coroutine context and
 * pass it on. Modifications of reactor context can be retrieved by ```coroutineContext[ReactorContext]```.
 *
 * Example usage:
 *
 * Passing reactor context from coroutine builder to reactor entity:
 *
 * ```
 * launch(Context.of(1, "1").asCoroutineContext()) {
 *   mono {
 *     assertEquals(coroutineContext[ReactorContext]!!.context.get(1), "1")
 *   }.subscribe()
 * }
 * ```
 *
 * Accessing modified reactor context enriched from downstream via coroutine context:
 *
 * ```
 * launch {
 *   mono {
 *     assertEquals(coroutineContext[ReactorContext]!!.context.get(2), "2")
 *   }.subscriberContext(Context.of(2, "2"))
 *    .subscribe()
 * }
 * ```
 */
public class ReactorContext(val context: Context) : AbstractCoroutineContextElement(ReactorContext) {
    companion object Key : CoroutineContext.Key<ReactorContext>
}


/**
 * Wraps [Context] into [ReactorContext] so that Reactor's [Context] elements
 * could be added as part of [CoroutineContext] and retrieved via ```coroutineContext[ReactorContext]```.
 */
public fun Context.asCoroutineContext(): CoroutineContext = ReactorContext(this)