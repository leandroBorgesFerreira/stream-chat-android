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

import io.getstream.chat.android.client.errors.ChatError
import io.getstream.chat.android.client.errors.ChatErrorCode
import io.getstream.chat.android.client.errors.ChatNetworkError
import io.getstream.chat.android.client.errors.ChatRequestError
import io.getstream.chat.android.client.parser.ChatParser
import io.getstream.chat.android.client.utils.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Callback
import retrofit2.Response
import retrofit2.awaitResponse
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class RetrofitCall<T : Any>(
    private val call: retrofit2.Call<T>,
    parser: ChatParser,
    scope: CoroutineScope,
    private val callbackExecutor: Executor,
) : CoroutineCall<T>(scope, call.asSuspendResult(parser)) {

    private var canceled = AtomicBoolean(false)

    override fun cancel() {
        super.cancel()
        canceled.set(true)
        call.cancel()
    }

    // override fun execute(): Result<T> {
    //     return execute(call)
    // }

    // override fun enqueue(callback: Call.Callback<T>) {
    //     println("[JcLog] RetrofitCall.enqueue")
    //     enqueue(call) {
    //         println("[JcLog] enqueue result: $it")
    //         if (!canceled.get()) {
    //             callback.onResult(it)
    //         }
    //     }
    // }
    //
    // private fun execute(call: retrofit2.Call<T>): Result<T> {
    //     return getResult(call)
    // }
    //
    // private fun enqueue(call: retrofit2.Call<T>, callback: (Result<T>) -> Unit) {
    //     println("[JcLog] RetrofitCall.enqueue retrofit call")
    //     call.enqueue(
    //         object : Callback<T> {
    //             override fun onResponse(call: retrofit2.Call<T>, response: Response<T>) {
    //                 println("[JcLog] RetrofitCall.onRespose $response").also {  }
    //                 callbackExecutor.execute {
    //                     callback(getResult(response))
    //                 }
    //             }
    //
    //             override fun onFailure(call: retrofit2.Call<T>, t: Throwable) {
    //                 println("[JcLog] RetrofitCall.onFailure $t")
    //                 println("[JcLog] callbackExecutor $callbackExecutor")
    //                 callbackExecutor.execute {
    //                     println("[JcLog] RetrofitCall.callbackExecutor.execute")
    //                     callback(failedResult(t))
    //                 }
    //             }
    //         }
    //     )
    // }
}

private fun <T : Any> retrofit2.Call<T>.asSuspendResult(parser: ChatParser): suspend CoroutineScope.() -> Result<T> =
    {
        println("[JcLog] Executing call")
        getResult(parser, this@asSuspendResult).also { println("[JcLog] Returning result $it") }
    }

private fun <T: Any> failedResult(t: Throwable): Result<T> {
    return Result(failedError(t))
}

private fun failedError(t: Throwable): ChatError {
    return when (t) {
        is ChatRequestError -> {
            ChatNetworkError.create(t.streamCode, t.message.toString(), t.statusCode, t.cause)
        }
        else -> {
            ChatNetworkError.create(ChatErrorCode.NETWORK_FAILED, t)
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun <T: Any> getResult(parser: ChatParser, retroCall: retrofit2.Call<T>): Result<T> = withContext(Dispatchers.IO){
    try {
        println("[JcLog] Executing retroCall")
        val retrofitResponse = retroCall.execute()
        println("[JcLog] retrofitResponse $retrofitResponse")
        getResult(parser, retrofitResponse)
    } catch (t: IOException) {
        println("[JcLog] on catch $t")
        failedResult(t)
    }
}

@Suppress("TooGenericExceptionCaught")
private fun <T: Any> getResult(parser: ChatParser, retrofitResponse: Response<T>): Result<T> {
    return if (retrofitResponse.isSuccessful) {
        try {
            Result(retrofitResponse.body()!!)
        } catch (t: Throwable) {
            Result(failedError(t))
        }
    } else {
        val errorBody = retrofitResponse.errorBody()

        if (errorBody != null) {
            Result(parser.toError(errorBody))
        } else {
            Result(parser.toError(retrofitResponse.raw()))
        }
    }
}