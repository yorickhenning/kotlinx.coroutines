/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.guava

import com.google.common.util.concurrent.*
import kotlinx.coroutines.*
import java.util.concurrent.*
import java.util.concurrent.CancellationException
import kotlin.coroutines.*

/**
 * Starts new coroutine and returns its results an an implementation of [ListenableFuture].
 * The running coroutine is cancelled when the resulting future is cancelled or otherwise completed.
 *
 * Coroutine context is inherited from a [CoroutineScope], additional context elements can be specified with [context] argument.
 * If the context does not have any dispatcher nor any other [ContinuationInterceptor], then [Dispatchers.Default] is used.
 * The parent job is inherited from a [CoroutineScope] as well, but it can also be overridden
 * with corresponding [coroutineContext] element.
 *
 * By default, the coroutine is immediately scheduled for execution.
 * Other options can be specified via `start` parameter. See [CoroutineStart] for details.
 * A value of [CoroutineStart.LAZY] is not supported
 * (since `ListenableFuture` framework does not provide the corresponding capability) and
 * produces [IllegalArgumentException].
 *
 * See [newCoroutineContext][CoroutineScope.newCoroutineContext] for a description of debugging facilities that are available for newly created coroutine.
 *
 * @param context additional to [CoroutineScope.coroutineContext] context of the coroutine.
 * @param start coroutine start option. The default value is [CoroutineStart.DEFAULT].
 * @param block the coroutine code.
 */
public fun <T> CoroutineScope.future(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> T
): ListenableFuture<T> {
    require(!start.isLazy) { "$start start is not supported" }
    val newContext = newCoroutineContext(context)
    val future = SettableFuture.create<T>()
    val coroutine = ListenableFutureCoroutine(newContext, future)
    Futures.addCallback(future, coroutine, CoroutineScope(newContext).asExecutor())
    coroutine.start(start, coroutine, block)
    return future
}

private class ListenableFutureCoroutine<T>(
    context: CoroutineContext,
    private val future: SettableFuture<T>
) : AbstractCoroutine<T>(context), FutureCallback<T> {
    /*
     * We register coroutine as callback to the future this coroutine completes.
     * But when future is cancelled externally, we'd like to cancel coroutine,
     * so we register on failure handler for this purpose
     */
    override fun onSuccess(result: T?) {
        // Do nothing
    }

    override fun onFailure(t: Throwable) {
        if (t is CancellationException) {
            cancel()
        }
    }

    override fun onCompleted(value: T) {
        future.set(value)
    }

    override fun onCancelled(cause: Throwable, handled: Boolean) {
        if (!future.setException(cause) && !handled) {
            // prevents loss of exception that was not handled by parent & could not be set to SettableFuture
            handleCoroutineException(context, cause)
        }
    }
}

/**
 * Converts this deferred value to the instance of [ListenableFuture].
 * The deferred value is cancelled when the resulting future is cancelled or otherwise completed.
 */
public fun <T> Deferred<T>.asListenableFuture(): ListenableFuture<T> = DeferredListenableFuture<T>(this)

private class DeferredListenableFuture<T>(
    private val deferred: Deferred<T>
) : AbstractFuture<T>() {
    init {
        deferred.invokeOnCompletion {
            try {
                set(deferred.getCompleted())
            } catch (t: Throwable) {
                setException(t)
            }
        }
    }
    override fun interruptTask() { deferred.cancel() }
}

/**
 * Converts this listenable future to an instance of [Deferred].
 * It is cancelled when the resulting deferred is cancelled.
 */
public fun <T> ListenableFuture<T>.asDeferred(): Deferred<T> {
    // Fast path if already completed
    if (isDone) {
        return try {
            @Suppress("UNCHECKED_CAST")
            CompletableDeferred(get() as T)
        } catch (e: Throwable) {
            // unwrap original cause from ExecutionException
            val original = (e as? ExecutionException)?.cause ?: e
            CompletableDeferred<T>().also { it.completeExceptionally(original) }
        }
    }
    val deferred = CompletableDeferred<T>()
    Futures.addCallback(this, object : FutureCallback<T> {
        override fun onSuccess(result: T?) {
            deferred.complete(result!!)
        }

        override fun onFailure(t: Throwable) {
            deferred.completeExceptionally(t)
        }
    }, CoroutineScope(deferred).asExecutor())

    deferred.invokeOnCompletion { cancel(false) }
    return deferred
}

/**
 * Awaits for completion of the future without blocking a thread.
 *
 * This suspending function is cancellable.
 * If the [Job] of the current coroutine is cancelled or completed while this suspending function is waiting, this function
 * stops waiting for the future and immediately resumes with [CancellationException][kotlinx.coroutines.CancellationException].
 *
 * This method is intended to be used with one-shot futures, so on coroutine cancellation future is cancelled as well.
 * If cancelling given future is undesired, `future.asDeferred().await()` should be used instead.
 */
public suspend fun <T> ListenableFuture<T>.await(): T {
    try {
        if (isDone) return get() as T
    } catch (e: ExecutionException) {
        throw e.cause ?: e // unwrap original cause from ExecutionException
    }

    return suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
        val callback = ContinuationCallback(cont)
        Futures.addCallback(this, callback, CoroutineScope(cont.context).asExecutor())
        cont.invokeOnCancellation {
            cancel(false)
            callback.cont = null // clear the reference to continuation from the future's callback
        }
    }
}

private class ContinuationCallback<T>(
    @Volatile @JvmField var cont: Continuation<T>?
) : FutureCallback<T> {
    @Suppress("UNCHECKED_CAST")
    override fun onSuccess(result: T?) { cont?.resume(result as T) }
    override fun onFailure(t: Throwable) { cont?.resumeWithException(t) }
}

/**
 * Returns an [Executor] that uses the [CoroutineScope] to run executed Runnables.
 *
 * Using this Executor ensures inputs pass through coroutine dispatch and
 * continuation.
 *
 * This Executor can be used in place of
 * [com.google.common.util.concurrent.MoreExecutors.directExecutor]. Using `directExecutor()` to
 * schedule a [FutureCallback] has the negative property of non-deterministic execution scope:
 *
 * 1. If the [FutureCallback] is attached to a completed [ListenableFuture], the scheduling thread
 * will execute the callback inline, reentering from the call stack.
 * 2. If a [FutureCallback] is attached to an incomplete [ListenableFuture], the thread that
 * completes the [ListenableFuture] will call the callback, and won't reenter from the call stack
 * call stack.
 *
 * Coroutines (barring use of Start.UNDISPATCHED...) *don't* reenter on the stack when running a
 * continuation, avoiding an entire class of bugs by design.
 *
 * Using this Executor instead of a `directExecutor()` avoids reintroducing
 * non-deterministic-stack-reentrancy bugs to Coroutine-centric code.
 */
inline fun CoroutineScope.asExecutor(): Executor {
    return Executor {
        checkNotNull(it)
        this.async {
            it.run()
        }
    }
}

