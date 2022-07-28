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

package io.getstream.chat.android.compose.viewmodel.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getstream.sdk.chat.utils.extensions.isModerationFailed
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.client.models.Channel
import io.getstream.chat.android.client.models.ConnectionState
import io.getstream.chat.android.client.models.Message
import io.getstream.chat.android.client.models.Reaction
import io.getstream.chat.android.client.models.User
import io.getstream.chat.android.common.messagelist.MessageListController
import io.getstream.chat.android.common.state.Copy
import io.getstream.chat.android.common.state.Delete
import io.getstream.chat.android.common.state.DeletedMessageVisibility
import io.getstream.chat.android.common.state.Flag
import io.getstream.chat.android.common.state.MessageAction
import io.getstream.chat.android.common.state.MessageFooterVisibility
import io.getstream.chat.android.common.state.MessageMode
import io.getstream.chat.android.common.state.MuteUser
import io.getstream.chat.android.common.state.Pin
import io.getstream.chat.android.common.state.React
import io.getstream.chat.android.common.state.Reply
import io.getstream.chat.android.common.state.Resend
import io.getstream.chat.android.common.state.ThreadReply
import io.getstream.chat.android.compose.handlers.ClipboardHandler
import io.getstream.chat.android.compose.state.messages.MessagesState
import io.getstream.chat.android.compose.state.messages.MyOwn
import io.getstream.chat.android.compose.state.messages.NewMessageState
import io.getstream.chat.android.compose.state.messages.Other
import io.getstream.chat.android.compose.state.messages.SelectedMessageFailedModerationState
import io.getstream.chat.android.compose.state.messages.SelectedMessageOptionsState
import io.getstream.chat.android.compose.state.messages.SelectedMessageReactionsPickerState
import io.getstream.chat.android.compose.state.messages.SelectedMessageReactionsState
import io.getstream.chat.android.compose.state.messages.SelectedMessageState
import io.getstream.chat.android.compose.state.messages.list.CancelGiphy
import io.getstream.chat.android.compose.state.messages.list.GiphyAction
import io.getstream.chat.android.compose.state.messages.list.MessageFocusRemoved
import io.getstream.chat.android.compose.state.messages.list.MessageFocused
import io.getstream.chat.android.compose.state.messages.list.MessageItemState
import io.getstream.chat.android.compose.state.messages.list.MessageListItemState
import io.getstream.chat.android.compose.state.messages.list.SendGiphy
import io.getstream.chat.android.compose.state.messages.list.ShuffleGiphy
import io.getstream.chat.android.compose.state.messages.toMessagesState
import io.getstream.chat.android.compose.ui.util.isError
import io.getstream.chat.android.compose.ui.util.isSystem
import io.getstream.chat.android.compose.util.extensions.asState
import io.getstream.chat.android.offline.extensions.loadMessageById
import io.getstream.chat.android.offline.extensions.loadOlderMessages
import io.getstream.chat.android.offline.plugin.state.channel.thread.ThreadState
import io.getstream.logging.StreamLog
import io.getstream.logging.TaggedLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit
import io.getstream.chat.android.common.messagelist.GiphyAction as GiphyActionCommon
import io.getstream.chat.android.common.messagelist.CancelGiphy as CancelGiphyCommon
import io.getstream.chat.android.common.messagelist.SendGiphy as SendGiphyCommon
import io.getstream.chat.android.common.messagelist.ShuffleGiphy as ShuffleGiphyCommon

/**
 * ViewModel responsible for handling all the business logic & state for the list of messages.
 *
 * @param chatClient Used to connect to the API.
 * @param channelId The ID of the channel to load the messages for.
 * @param clipboardHandler Used to copy data from message actions to the clipboard.
 * @param messageLimit The limit of messages being fetched with each page od data.
 * @param enforceUniqueReactions Enables or disables unique message reactions per user.
 * @param showDateSeparators Enables or disables date separator items in the list.
 * @param showSystemMessages Enables or disables system messages in the list.
 * @param dateSeparatorThresholdMillis The threshold in millis used to generate date separator items, if enabled.
 * @param deletedMessageVisibility The behavior of deleted messages in the list and if they're visible or not.
 */
@Suppress("TooManyFunctions", "LargeClass")
public class MessageListViewModel(
    public val chatClient: ChatClient,
    private val channelId: String,
    private val clipboardHandler: ClipboardHandler,
    private val messageLimit: Int = DefaultMessageLimit,
    private val enforceUniqueReactions: Boolean = true,
    private val showDateSeparators: Boolean = true,
    private val showSystemMessages: Boolean = true,
    private val dateSeparatorThresholdMillis: Long = TimeUnit.HOURS.toMillis(DateSeparatorDefaultHourThreshold),
    private val deletedMessageVisibility: DeletedMessageVisibility = DeletedMessageVisibility.ALWAYS_VISIBLE,
    private val messageFooterVisibility: MessageFooterVisibility = MessageFooterVisibility.WithTimeDifference(),
    private val messageListController: MessageListController =
        MessageListController(
            channelId,
            chatClient,
            deletedMessageVisibility,
            showSystemMessages,
            showDateSeparators,
            dateSeparatorThresholdMillis,
            messageFooterVisibility
        ),
) : ViewModel() {

    /**
     * Holds information about the abilities the current user
     * is able to exercise in the given channel.
     *
     * e.g. send messages, delete messages, etc...
     * For a full list @see [io.getstream.chat.android.client.models.ChannelCapabilities].
     */
    private val ownCapabilities: StateFlow<Set<String>> = messageListController.ownCapabilities

    /**
     * State handler for the UI, which holds all the information the UI needs to render messages.
     *
     * It chooses between [threadMessagesState] and [messagesState] based on if we're in a thread or not.
     */
    public val currentMessagesState: MessagesState
        get() = if (isInThread) threadMessagesState else messagesState

    /**
     * State of the screen, for [MessageMode.Normal].
     */
    private var messagesState: MessagesState by mutableStateOf(MessagesState())

    /**
     * State of the screen, for [MessageMode.MessageThread].
     */
    private var threadMessagesState: MessagesState by mutableStateOf(MessagesState())

    /**
     * Holds the current [MessageMode] that's used for the messages list. [MessageMode.Normal] by default.
     */
    public val messageMode: MessageMode by messageListController.mode.asState(viewModelScope)

    /**
     * The information for the current [Channel].
     */
    public val channel: Channel by messageListController.channel.asState(viewModelScope)

    /**
     * The list of typing users.
     */
    public val typingUsers: List<User> by messageListController.typingUsers.asState(viewModelScope)

    /**
     * Set of currently active [MessageAction]s. Used to show things like edit, reply, delete and
     * similar actions.
     */
    public var messageActions: Set<MessageAction> by mutableStateOf(mutableSetOf())
        private set

    /**
     * Gives us information if we're currently in the [Thread] message mode.
     */
    public val isInThread: Boolean
        get() = messageListController.isInThread

    /**
     * Gives us information if we have selected a message.
     */
    public val isShowingOverlay: Boolean
        get() = messagesState.selectedMessageState != null || threadMessagesState.selectedMessageState != null

    /**
     * Gives us information about the online state of the device.
     */
    public val connectionState: StateFlow<ConnectionState> by chatClient.clientState::connectionState

    /**
     * Gives us information about the online state of the device.
     */
    public val isOnline: Flow<Boolean>
        get() = chatClient.clientState.connectionState.map { it == ConnectionState.CONNECTED }

    /**
     * Gives us information about the logged in user state.
     */
    public val user: StateFlow<User?>
        get() = chatClient.clientState.user

    /**
     * [Job] that's used to keep the thread data loading operations. We cancel it when the user goes
     * out of the thread state.
     */
    private var threadJob: Job? = null

    /**
     * Represents the latest message we've seen in the channel.
     */
    private var lastSeenChannelMessage: Message? by mutableStateOf(null)

    /**
     * Represents the latest message we've seen in the active thread.
     */
    private var lastSeenThreadMessage: Message? by mutableStateOf(null)

    /**
     * Represents the message we wish to scroll to.
     */
    private var scrollToMessage: Message? = null

    /**
     * Instance of [TaggedLogger] to log exceptional and warning cases in behavior.
     */
    private val logger = StreamLog.getLogger("Chat:MessageListViewModel")

    /**
     * Sets up the core data loading operations - such as observing the current channel and loading
     * messages and other pieces of information.
     */
    init {
        observeMessageListState()
        observeThreadListState()
    }

    /**
     * Starts observing the current channel. We take the data exposed by the message list controller and map it to
     * compose list.
     */
    private fun observeMessageListState() {
        viewModelScope.launch {
            messageListController.messageListState.collect {
                messagesState = it.toMessagesState()
                if (scrollToMessage != null) {
                    messagesState.messageItems.firstOrNull {
                        it is MessageItemState && it.message.id == scrollToMessage?.id
                    }?.let { focusMessage((it as MessageItemState).message.id) }
                }
            }
        }
    }

    private fun observeThreadListState() {
        viewModelScope.launch {
            messageListController.threadListState.collect {
                threadMessagesState = it.toMessagesState()
            }
        }
    }

    /**
     * Counts how many messages the user hasn't read already. This is based on what the last message they've seen is,
     * and the current message state.
     *
     * @param newMessageState The state that tells us if there are new messages in the list.
     * @return [Int] which describes how many messages come after the last message we've seen in the list.
     */
    private fun getUnreadMessageCount(newMessageState: NewMessageState? = currentMessagesState.newMessageState): Int {
        if (newMessageState == null || newMessageState == MyOwn) return 0

        val messageItems = currentMessagesState.messageItems
        val lastSeenMessagePosition =
            getLastSeenMessagePosition(if (isInThread) lastSeenThreadMessage else lastSeenChannelMessage)
        var unreadCount = 0

        for (i in 0..lastSeenMessagePosition) {
            val messageItem = messageItems[i]

            if (messageItem is MessageItemState && !messageItem.isMine && messageItem.message.deletedAt == null) {
                unreadCount++
            }
        }

        return unreadCount
    }

    /**
     * Gets the list position of the last seen message in the list.
     *
     * @param lastSeenMessage - The last message we saw in the list.
     * @return [Int] list position of the last message we've seen.
     */
    private fun getLastSeenMessagePosition(lastSeenMessage: Message?): Int {
        if (lastSeenMessage == null) return 0

        return currentMessagesState.messageItems.indexOfFirst {
            it is MessageItemState && it.message.id == lastSeenMessage.id
        }
    }

    /**
     * Attempts to update the last seen message in the channel or thread. We only update the last seen message the first
     * time the data loads and whenever we see a message that's newer than the current last seen message.
     *
     * @param message The message that is currently seen by the user.
     */
    public fun updateLastSeenMessage(message: Message) {
        val lastSeenMessage = if (isInThread) lastSeenThreadMessage else lastSeenChannelMessage

        if (lastSeenMessage == null) {
            updateLastSeenMessageState(message)
            return
        }

        if (message.id == lastSeenMessage.id) {
            return
        }

        val lastSeenMessageDate = lastSeenMessage.createdAt ?: Date()
        val currentMessageDate = message.createdAt ?: Date()

        if (currentMessageDate < lastSeenMessageDate) {
            return
        }
        updateLastSeenMessageState(message)
    }

    /**
     * Updates the state of the last seen message. Updates corresponding state based on [isInThread].
     *
     * @param currentMessage The current message the user sees.
     */
    private fun updateLastSeenMessageState(currentMessage: Message) {
        if (isInThread) {
            lastSeenThreadMessage = currentMessage

            threadMessagesState = threadMessagesState.copy(unreadCount = getUnreadMessageCount())
        } else {
            lastSeenChannelMessage = currentMessage

            messagesState = messagesState.copy(unreadCount = getUnreadMessageCount())
        }

        val latestMessage: MessageItemState? = currentMessagesState.messageItems.firstOrNull { messageItem ->
            messageItem is MessageItemState
        } as? MessageItemState

        if (currentMessage.id == latestMessage?.message?.id) {
            messageListController.markLastMessageRead()
        }
    }

    /**
     * Triggered when the user loads more data by reaching the end of the current messages.
     */
    @Deprecated(
        message = "Deprecated after implementing bi directional pagination.",
        replaceWith = ReplaceWith(
            "loadOlderMessages(messageId: String)",
            "io.getstream.chat.android.compose.viewmodel.messages"
        ),
        level = DeprecationLevel.WARNING
    )
    public fun loadMore() {
        if (chatClient.clientState.isOffline) return
        val messageMode = messageMode

        if (messageMode is MessageMode.MessageThread) {
            threadLoadMore(messageMode)
        } else {
            chatClient.loadOlderMessages(channelId, messageLimit).enqueue()
        }
    }

    /**
     * Loads newer messages of a channel following the currently newest loaded message. In case of threads this will
     * do nothing.
     *
     * @param messageId The id of the newest [Message] inside the messages list.
     */
    public fun loadNewerMessages(messageId: String) {
        messageListController.loadNewerMessages(messageId)
    }

    /**
     * Loads older messages of a channel following the currently oldest loaded message. Also will load older messages
     * of a thread.
     */
    public fun loadOlderMessages(): Unit = messageListController.loadOlderMessages(DefaultMessageLimit)

    /**
     * Load older messages for the specified thread [MessageMode.MessageThread.parentMessage].
     *
     * @param threadMode Current thread mode.
     */
    private fun threadLoadMore(threadMode: MessageMode.MessageThread) {
        threadMessagesState = threadMessagesState.copy(isLoadingMore = true)
        if (threadMode.threadState != null) {
            chatClient.getRepliesMore(
                messageId = threadMode.parentMessage.id,
                firstId = threadMode.threadState?.oldestInThread?.value?.id ?: threadMode.parentMessage.id,
                limit = DefaultMessageLimit,
            ).enqueue()
        } else {
            threadMessagesState = threadMessagesState.copy(isLoadingMore = false)
            logger.w { "Thread state must be not null for offline plugin thread load more!" }
        }
    }

    /**
     * Loads the selected message we wish to scroll to when the message can't be found in the current list.
     *
     * @param message The selected message we wish to scroll to.
     */
    private fun loadMessage(message: Message) {
        chatClient.loadMessageById(channelId, message.id).enqueue()
    }

    /**
     * Triggered when the user long taps on and selects a message.
     *
     * @param message The selected message.
     */
    public fun selectMessage(message: Message?) {
        if (message != null) {
            changeSelectMessageState(
                if (message.isModerationFailed(chatClient)) {
                    SelectedMessageFailedModerationState(
                        message = message,
                        ownCapabilities = ownCapabilities.value
                    )
                } else {
                    SelectedMessageOptionsState(
                        message = message,
                        ownCapabilities = ownCapabilities.value
                    )
                }

            )
        }
    }

    /**
     * Triggered when the user taps on and selects message reactions.
     *
     * @param message The message that contains the reactions.
     */
    public fun selectReactions(message: Message?) {
        if (message != null) {
            changeSelectMessageState(
                SelectedMessageReactionsState(
                    message = message,
                    ownCapabilities = ownCapabilities.value
                )
            )
        }
    }

    /**
     * Triggered when the user taps the show more reactions button.
     *
     * @param message The selected message.
     */
    public fun selectExtendedReactions(message: Message?) {
        if (message != null) {
            changeSelectMessageState(
                SelectedMessageReactionsPickerState(
                    message = message,
                    ownCapabilities = ownCapabilities.value
                )
            )
        }
    }

    /**
     * Changes the state of [threadMessagesState] or [messagesState] depending
     * on the thread mode.
     *
     * @param selectedMessageState The selected message state.
     */
    private fun changeSelectMessageState(selectedMessageState: SelectedMessageState) {
        if (isInThread) {
            threadMessagesState = threadMessagesState.copy(selectedMessageState = selectedMessageState)
        } else {
            messagesState = messagesState.copy(selectedMessageState = selectedMessageState)
        }
    }

    /**
     * Triggered when the user taps on a message that has a thread active.
     *
     * @param message The selected message with a thread.
     */
    public fun openMessageThread(message: Message) {
        loadThread(message)
    }

    /**
     * Used to dismiss a specific message action, such as delete, reply, edit or something similar.
     *
     * @param messageAction The action to dismiss.
     */
    public fun dismissMessageAction(messageAction: MessageAction) {
        this.messageActions = messageActions - messageAction
    }

    /**
     * Dismisses all message actions, when we cancel them in the rest of the UI.
     */
    public fun dismissAllMessageActions() {
        this.messageActions = emptySet()
    }

    /**
     * Triggered when the user selects a new message action, in the message overlay.
     *
     * We first remove the overlay, after which we consume the event and based on the type of the event,
     * we do different things, such as starting a thread & loading thread data, showing delete or flag
     * events and dialogs, copying the message, muting users and more.
     *
     * @param messageAction The action the user chose.
     */
    public fun performMessageAction(messageAction: MessageAction) {
        removeOverlay()

        when (messageAction) {
            is Resend -> resendMessage(messageAction.message)
            is ThreadReply -> {
                messageActions = messageActions + Reply(messageAction.message)
                loadThread(messageAction.message)
            }
            is Delete, is Flag -> {
                messageActions = messageActions + messageAction
            }
            is Copy -> copyMessage(messageAction.message)
            is MuteUser -> updateUserMute(messageAction.message.user)
            is React -> reactToMessage(messageAction.reaction, messageAction.message)
            is Pin -> updateMessagePin(messageAction.message)
            else -> {
                // no op, custom user action
            }
        }
    }

    /**
     *  Changes the current [messageMode] to be [Thread] with [ThreadState] and Loads thread data using ChatClient
     *  directly. The data is observed by using [ThreadState].
     *
     * @param parentMessage The message with the thread we want to observe.
     */
    private fun loadThread(parentMessage: Message) {
        messageListController.enterThreadMode(parentMessage)
    }

    /**
     * Removes the delete actions from our [messageActions], as well as the overlay, before deleting
     * the selected message.
     *
     * @param message Message to delete.
     */
    @JvmOverloads
    @Suppress("ConvertArgumentToSet")
    public fun deleteMessage(message: Message, hard: Boolean = false) {
        messageActions = messageActions - messageActions.filterIsInstance<Delete>()
        removeOverlay()

        messageListController.deleteMessage(message, hard)
    }

    /**
     * Removes the flag actions from our [messageActions], as well as the overlay, before flagging
     * the selected message.
     *
     * @param message Message to delete.
     */
    @Suppress("ConvertArgumentToSet")
    public fun flagMessage(message: Message) {
        messageActions = messageActions - messageActions.filterIsInstance<Flag>()
        removeOverlay()

        chatClient.flagMessage(message.id).enqueue()
    }

    /**
     * Retries sending a message that has failed to send.
     *
     * @param message The message that will be re-sent.
     */
    private fun resendMessage(message: Message) = messageListController.resendMessage(message)

    /**
     * Copies the message content using the [ClipboardHandler] we provide. This can copy both
     * attachment and text messages.
     *
     * @param message Message with the content to copy.
     */
    private fun copyMessage(message: Message) {
        clipboardHandler.copyMessage(message)
    }

    /**
     * Mutes or unmutes the user that sent a particular message.
     *
     * @param user The user to mute or unmute.
     */
    private fun updateUserMute(user: User) = messageListController.updateUserMute(user)

    /**
     * Triggered when the user chooses the [React] action for the currently selected message. If the
     * message already has that reaction, from the current user, we remove it. Otherwise we add a new
     * reaction.
     *
     * @param reaction The reaction to add or remove.
     * @param message The currently selected message.
     */
    private fun reactToMessage(reaction: Reaction, message: Message) =
        messageListController.reactToMessage(reaction, message, enforceUniqueReactions)

    /**
     * Pins or unpins the message from the current channel based on its state.
     *
     * @param message The message to update the pin state of.
     */
    private fun updateMessagePin(message: Message) = messageListController.updateMessagePin(message)

    /**
     * Leaves the thread we're in and resets the state of the [messageMode] and both of the [MessagesState]s.
     *
     * It also cancels the [threadJob] to clean up resources.
     */
    public fun leaveThread() {
        messageListController.enterNormalMode()
        messagesState = messagesState.copy(selectedMessageState = null)
    }

    /**
     * Resets the [MessagesState]s, to remove the message overlay, by setting 'selectedMessage' to null.
     */
    public fun removeOverlay() {
        threadMessagesState = threadMessagesState.copy(selectedMessageState = null)
        messagesState = messagesState.copy(selectedMessageState = null)
    }

    /**
     * Clears the [NewMessageState] from our UI state, after the user taps on the "Scroll to bottom"
     * or "New Message" actions in the list or simply scrolls to the bottom.
     */
    public fun clearNewMessageState(): Unit = messageListController.clearNewMessageState()

    /**
     * Sets the focused message to be the message with the given ID, after which it removes it from
     * focus with a delay.
     *
     * @param messageId The ID of the message.
     */
    public fun focusMessage(messageId: String) {
        val messages = currentMessagesState.messageItems.map {
            if (it is MessageItemState && it.message.id == messageId) {
                it.copy(focusState = MessageFocused)
            } else {
                it
            }
        }

        viewModelScope.launch {
            updateMessages(messages)
            delay(RemoveMessageFocusDelay)
            removeMessageFocus(messageId)
        }
    }

    /**
     * Removes the focus from the message with the given ID.
     *
     * @param messageId The ID of the message.
     */
    private fun removeMessageFocus(messageId: String) {
        val messages = currentMessagesState.messageItems.map {
            if (it is MessageItemState && it.message.id == messageId) {
                it.copy(focusState = MessageFocusRemoved)
            } else {
                it
            }
        }

        if (scrollToMessage?.id == messageId) {
            scrollToMessage = null
        }

        updateMessages(messages)
    }

    /**
     * Updates the current message state with new messages.
     *
     * @param messages The list of new message items.
     * */
    private fun updateMessages(messages: List<MessageListItemState>) {
        if (isInThread) {
            this.threadMessagesState =
                threadMessagesState.copy(messageItems = messages)
        } else {
            this.messagesState =
                messagesState.copy(messageItems = messages)
        }
    }

    /**
     * Returns a message with the given ID from the [currentMessagesState].
     *
     * @param messageId The ID of the selected message.
     * @return The [Message] with the ID, if it exists.
     */
    public fun getMessageWithId(messageId: String): Message? {
        val messageItem =
            currentMessagesState.messageItems.firstOrNull { it is MessageItemState && it.message.id == messageId }

        return (messageItem as? MessageItemState)?.message
    }

    /**
     * Executes one of the actions for the given ephemeral giphy message.
     *
     * @param action The action to be executed.
     */
    public fun performGiphyAction(action: GiphyAction) {
        val actionToPerform: GiphyActionCommon = when (action) {
            is CancelGiphy -> CancelGiphyCommon(action.message)
            is SendGiphy -> SendGiphyCommon(action.message)
            is ShuffleGiphy -> ShuffleGiphyCommon(action.message)
        }
        messageListController.performGiphyAction(actionToPerform)
    }

    /**
     * Scrolls to message if in list otherwise get the message from backend. Does not work for threads.
     *
     * @param message The message we wish to scroll to.
     */
    public fun scrollToSelectedMessage(message: Message) {
        if (isInThread) return

        val isMessageInList = currentMessagesState.messageItems.firstOrNull {
            it is MessageItemState && it.message.id == message.id
        } != null
        scrollToMessage = message

        if (isMessageInList) {
            focusMessage(message.id)
        } else {
            loadMessage(message = message)
        }
    }

    /**
     * Requests that the list scrolls to the bottom to the newest messages. If the newest messages are loaded will set
     * scroll the list to the bottom. If they are not loaded will request the newest data and once loaded will scroll
     * to the bottom of the list.
     *
     * @param messageLimit The message count we wish to load from the API when loading new messages.
     * @param scrollToBottom Notifies the ui to scroll to the bottom if the newest messages are in the list or have been
     * loaded from the API.
     */
    public fun scrollToBottom(messageLimit: Int = DefaultMessageLimit, scrollToBottom: () -> Unit): Unit =
        messageListController.scrollToBottom(messageLimit, scrollToBottom)

    internal companion object {
        /**
         * The default threshold for showing date separators. If the message difference in hours is equal to this
         * number, then we show a separator, if it's enabled in the list.
         */
        internal const val DateSeparatorDefaultHourThreshold: Long = 4

        /**
         * The default limit for messages count in requests.
         */
        internal const val DefaultMessageLimit: Int = 30

        /**
         * Time in millis, after which the focus is removed.
         */
        private const val RemoveMessageFocusDelay: Long = 2000
    }
}
