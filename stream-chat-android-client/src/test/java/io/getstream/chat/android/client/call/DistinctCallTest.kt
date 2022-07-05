package io.getstream.chat.android.client.call

import io.getstream.chat.android.client.Mother
import io.getstream.chat.android.client.utils.Result
import io.getstream.chat.android.test.AsyncTestCall
import io.getstream.chat.android.test.TestCoroutineExtension
import io.getstream.chat.android.test.positiveRandomInt
import io.getstream.logging.StreamLog
import io.getstream.logging.kotlin.KotlinStreamLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.`should be equal to`
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        StreamLog.setLogger(KotlinStreamLogger())
    }

    @Test
    fun `Call should be executed asynchronous and return a valid result`() = runTest {
        val finished = AtomicBoolean(false)
        val testCall = AsyncTestCall(testCoroutines.scope, expectedResult) {
            delay(1000)
        }
        val spyCallBuilder = SpyCallBuilder(testCall)
        val call = DistinctCall(spyCallBuilder, uniqueKey) {
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