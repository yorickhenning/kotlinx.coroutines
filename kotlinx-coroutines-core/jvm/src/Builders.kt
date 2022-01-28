/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmMultifileClass
@file:JvmName("BuildersKt")
@file:OptIn(ExperimentalContracts::class)

package kotlinx.coroutines

import kotlin.contracts.*
import kotlin.coroutines.*

/**
 * Creates a new coroutine and **blocks** the current thread _interruptibly_ to immediately execute
 * it.
 *
 * [runBlocking] allows regular blocking code to call libraries that are written using coroutines.
 * [runBlocking] should be called by a test case or in a program's `main()` to "boostrap" into
 * coroutines.
 *
 * [runBlocking] should never be called from _in_ a coroutine. Blocking a thread is unnecessary
 * and inefficient. When a function is a `suspend` function, call [coroutineScope] rather than
 * [runBlocking] in order to introduce parallelism.
 *
 * [runBlocking] uses its input [context] as though it were the [CoroutineContext] that
 * constructed the blocking coroutine. Unlike a child coroutine built with [launch] or [async],
 * a [runBlocking] coroutine gets its [CoroutineStartInterceptor] and [ContinuationInterceptor]
 * from its parameter, rather than from the running context.
 *
 * When [CoroutineDispatcher] is explicitly specified in the [context], then the new coroutine runs in the context of
 * the specified dispatcher while the current thread is blocked. If the specified dispatcher is an event loop of another `runBlocking`,
 * then this invocation uses the outer event loop.
 *
 * If [context] does not contain a [CoroutineDispatcher], [runBlocking] will include add an event
 * loop [CoroutineDispatcher] to the [CoroutineContext], and execute continuations using the
 * blocked thread until [block] returns.
 *
 * When a [CoroutineStartInterceptor] is explicitly specified in the [context], it intercepts the
 * construction of _this_ coroutine. This is a special case that allows the thread calling
 * `runBlocking` to intercept the coroutine start before it blocks the thread.
 *
 * If the blocked thread is interrupted (see [Thread.interrupt]), this coroutine's job will be
 * cancelled. If the cancellation by interrupt succeeds, the running [runBlocking] function call
 * will complete by throwing [InterruptedException].
 *
 * See [newCoroutineContext][CoroutineScope.newCoroutineContext] for a description of debugging
 * facilities that are available for a newly created coroutine.
 *
 * @param context the context of the coroutine. The default value is an event loop on the current thread.
 * @param block the coroutine code.
 */
@Throws(InterruptedException::class)
public actual fun <T> runBlocking(context: CoroutineContext, block: suspend CoroutineScope.() -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val currentThread = Thread.currentThread()
    val continuationInterceptor = context[ContinuationInterceptor]
    val coroutineStartInterceptor = context[CoroutineStartInterceptor]
    val eventLoop: EventLoop?
    val newContext: CoroutineContext
    if (continuationInterceptor == null) {
        // create or use private event loop if no dispatcher is specified
        eventLoop = ThreadLocalEventLoop.eventLoop
        newContext = newCoroutineContext(
            callingContext = context + eventLoop,
            addedContext = EmptyCoroutineContext
        )
    } else {
        // See if context's interceptor is an event loop that we shall use (to support TestContext)
        // or take an existing thread-local event loop if present to avoid blocking it (but don't create one)
        eventLoop = (continuationInterceptor as? EventLoop)?.takeIf { it.shouldBeProcessedFromContext() }
            ?: ThreadLocalEventLoop.currentOrNull()
        newContext = newCoroutineContext(
            callingContext = context,
            addedContext = EmptyCoroutineContext
        )
    }
    val coroutine = BlockingCoroutine<T>(newContext, currentThread, eventLoop)
    coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
    return coroutine.joinBlocking()
}

private class BlockingCoroutine<T>(
    parentContext: CoroutineContext,
    private val blockedThread: Thread,
    private val eventLoop: EventLoop?
) : AbstractCoroutine<T>(parentContext, true, true) {

    override val isScopedCoroutine: Boolean get() = true

    override fun afterCompletion(state: Any?) {
        // wake up blocked thread
        if (Thread.currentThread() != blockedThread)
            unpark(blockedThread)
    }

    @Suppress("UNCHECKED_CAST")
    fun joinBlocking(): T {
        registerTimeLoopThread()
        try {
            eventLoop?.incrementUseCount()
            try {
                while (true) {
                    @Suppress("DEPRECATION")
                    if (Thread.interrupted()) throw InterruptedException().also { cancelCoroutine(it) }
                    val parkNanos = eventLoop?.processNextEvent() ?: Long.MAX_VALUE
                    // note: process next even may loose unpark flag, so check if completed before parking
                    if (isCompleted) break
                    parkNanos(this, parkNanos)
                }
            } finally { // paranoia
                eventLoop?.decrementUseCount()
            }
        } finally { // paranoia
            unregisterTimeLoopThread()
        }
        // now return result
        val state = this.state.unboxState()
        (state as? CompletedExceptionally)?.let { throw it.cause }
        return state as T
    }
}
