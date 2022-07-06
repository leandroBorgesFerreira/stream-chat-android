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

import io.getstream.chat.android.client.utils.Result
import io.getstream.logging.StreamLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Reusable wrapper around [Call] which delivers a single result to all subscribers.
 */
internal class DistinctCall<T : Any>(
    scope: CoroutineScope,
    private val uniqueKey: Int,
    private val timeoutInMillis: Long = TIMEOUT,
    private val callBuilder: () -> Call<T>,
    private val onFinished: () -> Unit,
) : Call<T> {

    init {
        StreamLog.i(TAG) { "<init> uniqueKey: $uniqueKey" }
    }

    private val distinctScope = scope + SupervisorJob(scope.coroutineContext.job)
    private val deferred = AtomicReference<Deferred<Result<T>>>()
    private val running = AtomicBoolean(false)

    internal fun originCall(): Call<T> = callBuilder()

    override fun execute(): Result<T> = runBlocking { await() }

    override fun enqueue(callback: Call.Callback<T>) {
        StreamLog.d(TAG) { "[enqueue] callback($$uniqueKey): $callback" }
        distinctScope.launch {
            val result = await()
            StreamLog.v(TAG) { "[enqueue] completed($uniqueKey)" }
            callback.onResult(result)
        }
    }

    override suspend fun await(): Result<T> = try {
        StreamLog.d(TAG) { "[await] uniqueKey: $uniqueKey" }
        if (running.compareAndSet(false, true)) {
            deferred.set(callBuilder().awaitAsync())
        }
        deferred.get()?.await()
            ?.also { StreamLog.v(TAG) { "[await] completed($uniqueKey)" } }
            ?: error("no deferred found")
    } catch (e: Throwable) {
        StreamLog.v(TAG) { "[await] failed($uniqueKey)" }
        Result.error(e)
    } finally {
        doFinally()
    }

    private fun Call<T>.awaitAsync(): Deferred<Result<T>> = distinctScope.async {
        StreamLog.d(TAG) { "[awaitAsync] uniqueKey($uniqueKey)" }
        withTimeout(timeoutInMillis) {
            await().also {
                StreamLog.v(TAG) { "[awaitAsync] completed($uniqueKey)" }
            }
        }
    }

    override fun cancel() {
        StreamLog.d(TAG) { "[cancel] uniqueKey: $uniqueKey" }
        try {
            distinctScope.coroutineContext.cancelChildren()
        } finally {
            doFinally()
        }
    }

    private fun doFinally() {
        StreamLog.v(TAG) { "[doFinally] uniqueKey: $uniqueKey" }
        deferred.set(null)
        running.set(false)
        onFinished()
    }

    private companion object {
        private const val TAG = "Chat:DistinctCall"
        private const val TIMEOUT = 60_000L
    }
}
