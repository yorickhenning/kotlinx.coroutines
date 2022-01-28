/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

import kotlin.coroutines.*

/**
 * Creates a new [CoroutineContext] for a new coroutine constructed in [this] [CoroutineScope].
 *
 * When a [CoroutineStartInterceptor] is present in [this] [CoroutineScope],]
 * [newCoroutineContext] calls it to construct the new context.
 *
 * Otherwise, it uses `this.coroutineContext + context` to create the new context.
 *
 * Before returning the new context, [newCoroutineContext] adds [Dispatchers.Default] to if no
 * [ContinuationInterceptor] was present in either [this] scope's [CoroutineContext] or in
 * [context].
 *
 * [newCoroutineContext] also adds debugging facilities to the returned context when debug features
 * are enabled.
 */
public expect fun CoroutineScope.newCoroutineContext(context: CoroutineContext): CoroutineContext

@PublishedApi
@Suppress("PropertyName")
internal expect val DefaultDelay: Delay

// countOrElement -- pre-cached value for ThreadContext.kt
internal expect inline fun <T> withCoroutineContext(context: CoroutineContext, countOrElement: Any?, block: () -> T): T
internal expect inline fun <T> withContinuationContext(continuation: Continuation<*>, countOrElement: Any?, block: () -> T): T
internal expect fun Continuation<*>.toDebugString(): String
internal expect val CoroutineContext.coroutineName: String?
