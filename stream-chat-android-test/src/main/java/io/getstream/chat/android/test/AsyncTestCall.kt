package io.getstream.chat.android.test

import io.getstream.chat.android.client.call.Call
import io.getstream.chat.android.client.utils.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

public class AsyncTestCall<T : Any>(
    public val scope: CoroutineScope,
    public val result: Result<T>,
    public val doWork: suspend () -> Unit
) : Call<T> {
    public var cancelled: Boolean = false

    override fun cancel() {
        cancelled = true
    }

    override fun enqueue(callback: Call.Callback<T>) {
        scope.launch {
            doWork()
            callback.onResult(result)
        }
    }

    override fun execute(): Result<T> = runBlocking { await() }
    override suspend fun await(): Result<T> = suspendCancellableCoroutine { continuation ->  
        enqueue { result ->
            continuation.resume(result)
        }
        continuation.invokeOnCancellation {
            cancel()
        }
    }
}