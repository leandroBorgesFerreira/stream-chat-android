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

package io.getstream.chat.android.guides.login

import io.getstream.chat.android.client.models.User

/**
 * A data class that encapsulates all the information needed to initialize
 * the SDK and connect to Stream servers.
 *
 * @param user The user that will be used for authentication.
 * @param token An instance of JWT token that will be used for authentication.
 */
data class LoginUser(
    val user: User,
    val token: String,
)
