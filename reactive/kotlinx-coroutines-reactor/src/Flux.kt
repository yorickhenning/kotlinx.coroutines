/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.reactor

import kotlin.coroutines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.reactive.*
import org.reactivestreams.*
import reactor.core.publisher.*
import reactor.core.*
import reactor.util.context.Context

/**
 * Creates cold reactive [Flux] that runs a given [block] in a coroutine.
 * Every time the returned flux is subscribed, it starts a new coroutine in the specified [context].
 * Coroutine emits items with `send`. Unsubscribing cancels running coroutine.
 *
 * Coroutine context is inherited from a [CoroutineScope], additional context elements can be specified with [context] argument.
 * If the context does not have any dispatcher nor any other [ContinuationInterceptor], then [Dispatchers.Default] is used.
 * The parent job is inherited from a [CoroutineScope] as well, but it can also be overridden
 * with corresponding [coroutineContext] element.
 *
 * Invocations of `send` are suspended appropriately when subscribers apply back-pressure and to ensure that
 * `onNext` is not invoked concurrently.
 *
 * | **Coroutine action**                         | **Signal to subscriber**
 * | -------------------------------------------- | ------------------------
 * | `send`                                       | `onNext`
 * | Normal completion or `close` without cause   | `onComplete`
 * | Failure with exception or `close` with cause | `onError`
 *
 * **Note: This is an experimental api.** Behaviour of publishers that work as children in a parent scope with respect
 *        to cancellation and error handling may change in the future.
 */

@ExperimentalCoroutinesApi
fun <T> CoroutineScope.flux(
    context: CoroutineContext = EmptyCoroutineContext,
    @BuilderInference block: suspend ProducerScope<T>.() -> Unit
): Flux<T> = Flux.from(reactorPublish(newCoroutineContext(context), block = block))


private fun <T> CoroutineScope.reactorPublish(
    context: CoroutineContext = EmptyCoroutineContext,
    @BuilderInference block: suspend ProducerScope<T>.() -> Unit
): Publisher<T> = Publisher { subscriber ->
    // specification requires NPE on null subscriber
    if (subscriber == null) throw NullPointerException("Subscriber cannot be null")
    val currentContext = if (subscriber is CoreSubscriber) subscriber.currentContext() else Context.empty()
    val reactorContext = (coroutineContext[ReactorContext]?.context?.putAll(currentContext) ?: currentContext).asCoroutineContext()
    val newContext = newCoroutineContext(context + reactorContext)
    val coroutine = PublisherCoroutine(newContext, subscriber)
    subscriber.onSubscribe(coroutine) // do it first (before starting coroutine), to avoid unnecessary suspensions
    coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
}