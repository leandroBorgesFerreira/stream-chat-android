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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Reusable wrapper around [Call] which delivers a single result to all subscribers.
 */
internal class DistinctCall<T : Any>(
    internal val callBuilder: () -> Call<T>,
    private val uniqueKey: Int,
    private val onFinished: () -> Unit,
) : Call<T> {

    init {
        StreamLog.i(TAG) { "<init> uniqueKey: $uniqueKey" }
    }

    private val delegate = AtomicReference<Call<T>>()
    private val isRunning = AtomicBoolean(false)
    private val subscribers = arrayListOf<Call.Callback<T>>()

    override fun execute(): Result<T> = runBlocking { await() }

    override fun enqueue(callback: Call.Callback<T>) {
        StreamLog.d(TAG) { "[enqueue] callback($$uniqueKey): $callback" }
        subscribers.addCallback(callback)
        if (isRunning.compareAndSet(false, true)) {
            delegate.set(
                callBuilder().apply {
                    enqueue { result ->
                        StreamLog.v(TAG) { "[enqueue] completed($uniqueKey)" }
                        try {
                            subscribers.notifyResult(result)
                        } finally {
                            doFinally()
                        }
                    }
                }
            )
        }
    }

    override suspend fun await(): Result<T> {
        StreamLog.d(TAG) { "[await] uniqueKey: $uniqueKey" }
        return suspendCancellableCoroutine { continuation ->
            enqueue { result ->
                StreamLog.v(TAG) { "[await] completed($uniqueKey)" }
                continuation.resume(result)
            }
            continuation.invokeOnCancellation {
                StreamLog.v(TAG) { "[await] canceled($uniqueKey)" }
                cancel()
            }
        }
    }

    override fun cancel() {
        StreamLog.d(TAG) { "[cancel] uniqueKey: $uniqueKey" }
        try {
            delegate.get()?.cancel()
        } finally {
            doFinally()
        }
    }

    private fun doFinally() {
        StreamLog.v(TAG) { "[doFinally] uniqueKey: $uniqueKey" }
        synchronized(subscribers) {
            subscribers.clear()
        }
        isRunning.set(false)
        delegate.set(null)
        onFinished()
    }

    private fun MutableList<Call.Callback<T>>.addCallback(callback: Call.Callback<T>) {
        synchronized(lock = this) {
            add(callback)
        }
    }

    private fun List<Call.Callback<T>>.notifyResult(result: Result<T>) {
        synchronized(lock = this) {
            forEach { callback ->
                try {
                    callback.onResult(result)
                } catch (_: Throwable) {
                    /* no-op */
                }
            }
        }
    }

    private companion object {
        private const val TAG = "Chat:DistinctCall"
    }
}

