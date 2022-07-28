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

package io.getstream.chat.android.compose.state.messages.list

import io.getstream.chat.android.client.models.Message
import io.getstream.chat.android.client.models.User
import io.getstream.chat.android.common.model.MessageListItem
import io.getstream.chat.android.common.state.DeletedMessageVisibility
import java.util.Date

/**
 * Represents the type of an item that we're showing in the message list.
 */
public sealed class MessageListItemState

/**
 * Represents a date separator in the list.
 *
 * @param date The date of the message that we're showing a separator for.
 */
public data class DateSeparatorState(val date: Date) : MessageListItemState()

/**
 * Represents a separator between thread parent message and thread replies.
 *
 * @param replyCount The number of thread replies to the message.
 */
public data class ThreadSeparatorState(val replyCount: Int) : MessageListItemState()

/**
 * Represents a message generated by a system event, such as updating the channel or muting a user.
 *
 * @param message The message to show.
 */
public data class SystemMessageState(val message: Message) : MessageListItemState()

// TODO
public data class TypingItemState(val users: List<User>): MessageListItemState()

/**
 * Represents each message item we show in the list of messages.
 *
 * @param message The message to show.
 * @param groupPosition The position of this message in a group, if it belongs to one.
 * @param parentMessageId The id of the parent message, when we're in a thread.
 * @param isMine If the message is of the current user or someone else.
 * @param focusState The focus state of the message.
 * @param isInThread If the message is being displayed in a thread.
 * @param currentUser The currently logged in user.
 * @param isMessageRead If the message is read by any member.
 * @param shouldShowFooter If the message footer should be shown.
 * @param deletedMessageVisibility The deleted message visibility logic used to show or hide messages in the list.
 */
public data class MessageItemState(
    val message: Message,
    val groupPosition: MessageItemGroupPosition = MessageItemGroupPosition.None,
    val parentMessageId: String? = null,
    val isMine: Boolean = false,
    val focusState: MessageFocusState? = null,
    val isInThread: Boolean = false,
    val currentUser: User? = null,
    val isMessageRead: Boolean = false,
    val shouldShowFooter: Boolean = false,
    val deletedMessageVisibility: DeletedMessageVisibility = DeletedMessageVisibility.ALWAYS_VISIBLE,
) : MessageListItemState()
