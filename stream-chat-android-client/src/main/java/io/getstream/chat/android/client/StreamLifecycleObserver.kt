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

package io.getstream.chat.android.client

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.getstream.chat.android.core.internal.coroutines.DispatcherProvider
import io.getstream.logging.StreamLog
import kotlinx.coroutines.withContext

internal class StreamLifecycleObserver(
    private val lifecycle: Lifecycle,
    private val handler: LifecycleHandler,
) : DefaultLifecycleObserver {

    private val logger = StreamLog.getLogger("Chat:LifecycleObserver")

    private var recurringResumeEvent = false

    @Volatile
    private var isObserving = false

    suspend fun observe() {
        logger.d { "[observe] no args" }
        if (isObserving.not()) {
            isObserving = true
            withContext(DispatcherProvider.Main) {
                lifecycle.addObserver(this@StreamLifecycleObserver)
                logger.v { "[observe] subscribed" }
            }
        }
    }

    suspend fun dispose() {
        logger.d { "[dispose] no args" }
        if (isObserving) {
            withContext(DispatcherProvider.Main) {
                lifecycle.removeObserver(this@StreamLifecycleObserver)
                logger.v { "[dispose] unsubscribed" }
            }
        }
        isObserving = false
        recurringResumeEvent = false
    }

    override fun onResume(owner: LifecycleOwner) {
        logger.d { "[onResume] owner: $owner" }
        // ignore event when we just started observing the lifecycle
        if (recurringResumeEvent) {
            handler.resume()
        }
        recurringResumeEvent = true
    }

    override fun onStop(owner: LifecycleOwner) {
        logger.d { "[onStop] owner: $owner" }
        handler.stopped()
    }
}

internal interface LifecycleHandler {
    fun resume()
    fun stopped()
}
