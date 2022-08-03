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

package io.getstream.chat.android.client.experimental.socket

import io.getstream.chat.android.client.errors.ChatNetworkError
import io.getstream.chat.android.client.socket.SocketFactory

/**
 * Events
 */
internal sealed class Event {

    /**
     * Event to start a new connection.
     */
    data class Connect(
        val connectionConf: SocketFactory.ConnectionConf,
        val isReconnection: Boolean,
    ) : Event()

    /**
     * Event to notify some WebSocket event has been lost.
     */
    object WebSocketEventLost : Event()

    /**
     * Event to notify Network is not available.
     */
    object NetworkNotAvailable : Event()

    /**
     * Event to notify an Unrecoverable Error happened on the WebSocket connection.
     */
    data class UnrecoverableError(val error: ChatNetworkError) : Event()

    /**
     * Event to notify a network Error happened on the WebSocket connection.
     */
    data class NetworkError(val error: ChatNetworkError) : Event()

    /**
     * Event to stop WebSocket connection required by user.
     */
    object RequiredDisconnection : Event()

    /**
     * Event to stop WebSocket connection.
     */
    object Stop : Event()
}
