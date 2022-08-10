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
import io.getstream.chat.android.core.internal.InternalStreamChatApi

@InternalStreamChatApi
/**
 * Call that uses Thread instead of Coroutines.
 */
public class ThreadCall<T : Any>(
    private val task: () -> T,
) : Call<T> {

    private var thread: Thread? = null

    @Suppress("TooGenericExceptionCaught")
    override fun execute(): Result<T> = try {
        Result.success(task())
    } catch (e: Throwable) {
        Result.error(e)
    }

    override fun enqueue(callback: Call.Callback<T>) {
        thread = Thread { callback.onResult(execute()) }
        thread?.start()
    }

    override suspend fun await(): Result<T> {
        return execute()
    }

    override fun cancel() {
        thread?.interrupt()
    }
}
