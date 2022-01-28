/*
 * Copyright 2016-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

import kotlin.coroutines.*

/**
 * Called to construct the `CoroutineContext` for a new coroutine.
 *
 * When a `CoroutineStartInterceptor` is present in a parent coroutine’s
 * `CoroutineContext`, Coroutines will call it to construct a new child coroutine’s
 * `CoroutineContext`.
 *
 * The `CoroutineStartInterceptor` can insert, remove, copy, or modify
 * `CoroutineContext.Elements` passed to the new child coroutine.
 *
 * The “default implementation” of `interceptContext()` used by coroutine builders
 * ([coroutineScope], [launch], [async]) when no [CoroutineStartInterceptor] is included, is
 * `callingContext + addedContext`. This default folds the the coroutine builder’s `context`
 * parameter left onto the parent coroutine's `coroutineContext`.
 *
 * This API is delicate and performance sensitive.
 *
 * Since `interceptContext()` is called each time a new coroutine is created, its
 * implementation has a disproportionate impact on coroutine performance.
 *
 * Since `interceptContext` _replaces_ coroutine context inheritance, it can arbitrarily change how
 * coroutines inherit their scope. In order for a child coroutine’s `CoroutineContext`
 * to be inherited as described in documentation, an override of `interceptContext()`
 * __must__ add `callingContext` and `addedContext` to form the return value, with
 * `callingContext` to the left of `addedContext` in the sum.
 *
 * These example statements all preserve "normal" inheritance and modify a custom element:
 *
 * ```
 * callingContext + addedContext
 * callingContext + CustomContextElement() + addedContext
 * callingContext + addedContext + CustomContextElement()
 * ```
 *
 * These examples _break `Job` inheritance_, because they drop or reverse `callingContext` folding:
 *
 * ```
 * addedContext + callingContext
 * CustomContextElement() + addedContext
 * ```
 */
@ExperimentalCoroutinesApi
@DelicateCoroutinesApi
public interface CoroutineStartInterceptor : CoroutineContext.Element {

    public companion object Key : CoroutineContext.Key<CoroutineStartInterceptor>

    /**
     * Called to construct the `CoroutineContext` for a new coroutine.
     *
     * The `CoroutineContext` returned by `interceptContext()` will be the `CoroutineContext` used
     * by the child coroutine.
     *
     * [callingContext] is the `CoroutineContext` of the coroutine constructing the coroutine. If
     * the coroutine is getting constructed by `runBlocking {}` outside of a running coroutine,
     * [callingContext] will be the `EmptyCoroutineContext`.
     *
     * [addedContext] is the `CoroutineContext` passed as a parameter to the coroutine builder. If
     * no `CoroutineContext` was passed, [addedContext will be the `EmptyCoroutineContext`.
     *
     * Consider this example:
     *
     * ```
     * runBlocking(CustomCoroutineStartInterceptor()) {
     *  async(CustomContextElement()) {
     *  }
     * }
     * ```
     *
     * In this arrangement, `CustomCoroutineStartInterceptor.interceptContext()` is called
     * to construct the `CoroutineContext` for the `async` coroutine. When
     * `interceptContext()` is called, `callingContext` will contain
     * `CustomCoroutineStartInterceptor`. `addedContext` will contain the new
     * `CustomContextElement`.
     */
    public fun interceptContext(
        callingContext: CoroutineContext,
        addedContext: CoroutineContext
    ): CoroutineContext
}

