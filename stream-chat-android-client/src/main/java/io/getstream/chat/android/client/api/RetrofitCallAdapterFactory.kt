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

package io.getstream.chat.android.client.api

import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.getstream.chat.android.client.call.Call
import io.getstream.chat.android.client.call.RetrofitCall
import io.getstream.chat.android.client.parser.ChatParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import retrofit2.CallAdapter
import retrofit2.Retrofit
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.Executor

internal class RetrofitCallAdapterFactory private constructor(
    private val chatParser: ChatParser,
    private val callbackExecutor: Executor,
    private val coroutineScope: CoroutineScope,
) : CallAdapter.Factory() {

    override fun get(
        returnType: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): CallAdapter<*, *>? {
        if (getRawType(returnType) != RetrofitCall::class.java) {
            return null
        }
        if (returnType !is ParameterizedType) {
            throw IllegalArgumentException("Call return type must be parameterized as Call<Foo>")
        }
        val responseType: Type = getParameterUpperBound(0, returnType)
        return RetrofitCallAdapter<Any>(responseType, chatParser, callbackExecutor, coroutineScope)
    }

    companion object {
        private val mainThreadExecutor: Executor = object : Executor {
            // val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
            override fun execute(command: Runnable?) {
                println("[JcLog] executing command: $command")
                // println("[JcLog] handler: $handler")
                GlobalScope.launch(Dispatchers.Main) {
                    println("[JcLog] inside of runBlocking: $command")
                }
                command?.run()
                // command?.let(handler::post)
            }
        }

        private val coroutineExecutor = Dispatchers.IO.asExecutor()

        fun create(
            chatParser: ChatParser,
            callbackExecutor: Executor? = null,
            coroutineScope: CoroutineScope,
        ): RetrofitCallAdapterFactory = RetrofitCallAdapterFactory(chatParser, callbackExecutor ?: coroutineExecutor, coroutineScope)
    }
}

internal class RetrofitCallAdapter<T : Any>(
    private val responseType: Type,
    private val parser: ChatParser,
    private val callbackExecutor: Executor,
    private val coroutineScope: CoroutineScope,
) : CallAdapter<T, Call<T>> {

    override fun adapt(call: retrofit2.Call<T>): Call<T> {
        return RetrofitCall(call, parser, coroutineScope, callbackExecutor)
    }

    override fun responseType(): Type = responseType
}
