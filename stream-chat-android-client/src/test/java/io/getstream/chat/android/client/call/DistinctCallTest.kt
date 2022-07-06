/*
 * Copyright (c) 2014-2022 Stream.io Inc. All rights reserved.
 *
 * Licensed under the Stream License;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://github.com/GetStream/stream-chat-android/blob/main/LICENSE
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.getstream.chat.android.client.call

import io.getstream.chat.android.client.Mother
import io.getstream.chat.android.client.utils.Result
import io.getstream.chat.android.client.utils.stringify
import io.getstream.chat.android.test.MockRetrofitCall
import io.getstream.chat.android.test.TestCoroutineExtension
import io.getstream.chat.android.test.positiveRandomInt
import io.getstream.logging.StreamLog
import io.getstream.logging.kotlin.KotlinStreamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.`should be equal to`
import org.amshove.kluent.`should be instance of`
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "DistinctCallTest"
private const val TIMEOUT = 60_000L

internal class DistinctCallTest {

    companion object {
        @JvmField
        @RegisterExtension
        val testCoroutines = TestCoroutineExtension()
    }

    private val expectedResult = Result.success(Mother.randomString())
    private val uniqueKey = positiveRandomInt()

    @BeforeEach
    fun setup() {
        StreamLog.setValidator { _, _ -> true }
        StreamLog.setLogger(
            KotlinStreamLogger(
                now = { testCoroutines.dispatcher.scheduler.currentTime }
            )
        )
    }

    @Test
    fun `Call should be executed asynchronous and return a valid result`() = runTest {
        val finished = AtomicBoolean(false)
        val testCall = MockRetrofitCall(testCoroutines.scope, expectedResult) {
            delay(1000)
        }
        val spyCallBuilder = SpyCallBuilder(testCall)
        val call = DistinctCall(testCoroutines.scope, uniqueKey, TIMEOUT, spyCallBuilder) {
            finished.set(true)
        }

        (0..positiveRandomInt(10))
            .map { async { call.await() } }
            .awaitAll()
            .forEach { actualResult ->
                actualResult `should be equal to` expectedResult
            }

        finished.get() `should be equal to` true
        spyCallBuilder.assertInvocationCount { invocationCount ->
            invocationCount `should be equal to` 1
        }
    }

    @Test
    fun testCancellation() = runTest(dispatchTimeoutMs = 5_000) {
        val finished = AtomicBoolean(false)
        val testCall = MockRetrofitCall(testCoroutines.scope, expectedResult) {
            delay(1500)
        }
        val spyCallBuilder = SpyCallBuilder(testCall)
        val distinctCall = DistinctCall(testCoroutines.scope, uniqueKey, TIMEOUT, spyCallBuilder) {
            finished.set(true)
        }

        val deferred = (0..positiveRandomInt(10)).map { index ->
            async {
                distinctCall.await()
            }.also {
                StreamLog.v(TAG) { "[deadLock] async($index) scheduled" }
            }
        }

        delay(100)

        distinctCall.cancel()

        delay(100)

        deferred.forEach {
            val actualResult = it.await()
            StreamLog.d(TAG) { "[deadLock] actualResult: ${actualResult.stringify { it }}" }
            actualResult.isError `should be equal to` true
            val error = actualResult.error()
            error.cause `should be instance of` CancellationException::class.java
        }
    }

    @Test
    fun testTimeout() = runTest {
        val finished = AtomicBoolean(false)
        val testCall = MockRetrofitCall(testCoroutines.scope, expectedResult) {
            while(true) {
                delay(60_000L)
            }
        }
        val spyCallBuilder = SpyCallBuilder(testCall)
        val distinctCall = DistinctCall(testCoroutines.scope, uniqueKey, 20_000, spyCallBuilder) {
            finished.set(true)
        }

        val deferred = (0..positiveRandomInt(10)).map { index ->
            async {
                distinctCall.await()
            }.also {
                StreamLog.v(TAG) { "[deadLock] async($index) scheduled" }
            }
        }

        deferred.forEach {
            val actualResult = it.await()
            StreamLog.d(TAG) { "[deadLock] actualResult: ${actualResult.stringify { it }}" }
            actualResult.isError `should be equal to` true
            val error = actualResult.error()
            error.cause `should be instance of` TimeoutCancellationException::class.java
        }
    }

    private class SpyCallBuilder<T : Any>(
        private val call: Call<T>
    ) : () -> Call<T> {

        private val counter = AtomicInteger(0)

        override fun invoke(): Call<T> {
            counter.incrementAndGet()
            return call
        }

        fun assertInvocationCount(assertionBlock: (Int) -> Unit) {
            assertionBlock(counter.get())
        }
    }
}
