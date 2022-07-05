package io.getstream.chat.android.client.call

import io.getstream.chat.android.client.BlockedCall
import io.getstream.chat.android.client.Mother
import io.getstream.chat.android.client.utils.Result
import io.getstream.chat.android.test.TestCoroutineExtension
import io.getstream.chat.android.test.positiveRandomInt
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.`should be equal to`
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.Mockito
import org.mockito.kotlin.eq
import org.mockito.kotlin.spy

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
        val blockedCall = BlockedCall(validResult)
        val spyCallBuilder = SpyCallBuilder(blockedCall)
        val call = DistinctCall(testCoroutines.scope, spyCallBuilder, uniqueKey) { println("doFinal") }
        val callback: Call.Callback<String> = spy()

        val deferredResults = (0..positiveRandomInt(10)).map { async { call.await() } }
        // call.enqueue(callback)
        // call.enqueue(callback)
        blockedCall.unblock()

        deferredResults.forEach {
            it.await() `should be equal to` validResult
        }
        // Mockito.verify(callback).onResult(eq(validResult))
        spyCallBuilder.`should be invoked once`()
        blockedCall.isStarted() `should be equal to` true
        blockedCall.isCompleted() `should be equal to` true
        blockedCall.isCanceled() `should be equal to` false
    }

    private class SpyCallBuilder<T : Any>(private val call: Call<T>) : () -> Call<T> {
        private var invocations = 0

        override fun invoke(): Call<T> {
            invocations++
            return call
        }

        fun `should be invoked once`() {
            invocations `should be equal to` 1
        }

        fun `should not be invoked`() {
            if (invocations > 0) {
                throw AssertionError("Consumer never wanted to be invoked but invoked")
            }
        }
    }
}