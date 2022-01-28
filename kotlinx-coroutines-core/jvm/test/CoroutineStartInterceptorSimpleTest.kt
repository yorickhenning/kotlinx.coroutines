/*
 * Copyright 2016-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

import org.junit.Test
import kotlin.coroutines.*
import kotlin.test.*

class CoroutineStartInterceptorSimpleTest: TestBase() {

    /**
     * A [CoroutineContext.Element] holding an integer, used to enumerate elements allocated
     * during context construction.
     */
    private class IntegerContextElement(
        val number: Int
    ): CoroutineContext.Element {
        public companion object Key : CoroutineContext.Key<IntegerContextElement>
        override val key = Key
    }

    /**
     * Inserts a new, unique [IntegerContextElement] into each new coroutine context constructed,
     * overriding any present.
     */
    class AddElementInterceptor : CoroutineStartInterceptor,
        AbstractCoroutineContextElement(CoroutineStartInterceptor.Key) {
        private var integerSource = 0

        override fun interceptContext(
            callingContext: CoroutineContext,
            addedContext: CoroutineContext
        ): CoroutineContext {
            integerSource += 1
            return callingContext + addedContext + IntegerContextElement(integerSource)
        }
    }

    @Test
    fun testContextInterceptorOverridesContextElement() = runTest {
        assertNull(coroutineContext[IntegerContextElement.Key])

        runBlocking(AddElementInterceptor()) {

        }
    }

    @Test
    fun testAsyncDoesNotInterceptFromAddedContext() = runTest {
        assertNull(coroutineContext[IntegerContextElement.Key])

        async(AddElementInterceptor()) {
            assertNull(coroutineContext[IntegerContextElement.Key])
        }.join()
    }

    @Test
    fun testLaunchDoesNotInterceptFromAddedContext() = runTest {
        assertNull(coroutineContext[IntegerContextElement.Key])

        launch(AddElementInterceptor()) {
            assertNull(coroutineContext[IntegerContextElement.Key])
        }.join()
    }

    @Test
    fun testRunBlockingInterceptsFromAddedContext() = runTest {
        assertNull(coroutineContext[IntegerContextElement.Key])

        runBlocking(AddElementInterceptor()) {
            assertEquals(
                1,
                coroutineContext[IntegerContextElement.Key]!!.number
            )
        }
    }

    @Test
    fun testChildCoroutineContextInterceptedFromCallingContext() = runTest {
        assertNull(coroutineContext[IntegerContextElement.Key])

        launch(AddElementInterceptor()) {
            launch {
                assertEquals(
                    1,
                    coroutineContext[IntegerContextElement.Key]!!.number
                )
            }.join()
            launch {
                assertEquals(
                    2,
                    coroutineContext[IntegerContextElement.Key]!!.number
                )
            }.join()
        }
    }

    @Test
    fun testChildCoroutineContextInterceptedFromCallingContextNotAddedContext() = runTest {
        assertNull(coroutineContext[IntegerContextElement.Key])

        launch(AddElementInterceptor()) {
            launch {}.join()

            launch(AddElementInterceptor()) {// Count of this interceptor is 0.
                assertEquals(
                    2,
                    coroutineContext[IntegerContextElement.Key]!!.number
                )
                launch {
                    assertEquals(
                        1, // Parent coroutine's context intercepted.
                        coroutineContext[IntegerContextElement.Key]!!.number
                    )
                }.join()
            }.join()
        }
    }
}