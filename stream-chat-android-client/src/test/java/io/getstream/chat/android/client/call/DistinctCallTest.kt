package io.getstream.chat.android.client.call

import io.getstream.chat.android.client.Mother
import io.getstream.chat.android.client.utils.Result
import io.getstream.chat.android.test.AsyncTestCall
import io.getstream.chat.android.test.TestCoroutineExtension
import io.getstream.chat.android.test.positiveRandomInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.`should be equal to`
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

    private val resultValue = Mother.randomString()
    private val validResult: Result<String> = Result.success(resultValue)
    private val uniqueKey = positiveRandomInt()

    @Test
    fun `Call should be executed asynchronous and return a valid result`() = runTest {
        val finished = AtomicBoolean(false)
        val testCall = AsyncTestCall(testCoroutines.scope, validResult) {
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
                actualResult `should be equal to` validResult
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

        fun assertInvocationCount(block: (Int) -> Unit) {
            block(counter.get())
        }
    }
}