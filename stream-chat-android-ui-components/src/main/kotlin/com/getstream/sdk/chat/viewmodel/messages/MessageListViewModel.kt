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

package com.getstream.sdk.chat.viewmodel.messages

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.viewModelScope
import com.getstream.sdk.chat.adapter.MessageListItem
import com.getstream.sdk.chat.enums.GiphyAction
import com.getstream.sdk.chat.model.ModelType
import com.getstream.sdk.chat.utils.extensions.getCreatedAtOrThrow
import com.getstream.sdk.chat.view.messages.MessageListItemWrapper
import com.getstream.sdk.chat.view.messages.toMessageListItemWrapper
import com.getstream.sdk.chat.viewmodel.messages.MessageListViewModel.DateSeparatorHandler
import com.getstream.sdk.chat.viewmodel.messages.MessageListViewModel.MessagePositionHandler
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.client.call.Call
import io.getstream.chat.android.client.call.enqueue
import io.getstream.chat.android.client.errors.ChatError
import io.getstream.chat.android.client.models.Attachment
import io.getstream.chat.android.client.models.Channel
import io.getstream.chat.android.client.models.Flag
import io.getstream.chat.android.client.models.Message
import io.getstream.chat.android.client.models.Reaction
import io.getstream.chat.android.client.models.User
import io.getstream.chat.android.client.setup.state.ClientState
import io.getstream.chat.android.client.utils.Result
import io.getstream.chat.android.common.messagelist.MessageListController
import io.getstream.chat.android.common.state.DeletedMessageVisibility
import io.getstream.chat.android.common.state.MessageFooterVisibility
import io.getstream.chat.android.common.state.MessageMode
import io.getstream.chat.android.offline.extensions.loadMessageById
import io.getstream.chat.android.offline.extensions.setMessageForReply
import io.getstream.chat.android.offline.plugin.state.channel.ChannelState
import io.getstream.chat.android.offline.plugin.state.channel.thread.ThreadState
import io.getstream.logging.StreamLog
import io.getstream.logging.TaggedLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.getstream.chat.android.livedata.utils.Event as EventWrapper
import io.getstream.chat.android.common.messagelist.CancelGiphy as CancelGiphyCommon
import io.getstream.chat.android.common.messagelist.SendGiphy as SendGiphyCommon
import io.getstream.chat.android.common.messagelist.ShuffleGiphy as ShuffleGiphyCommon

/**
 * View model class for [com.getstream.sdk.chat.view.MessageListView].
 * Responsible for updating the list of messages.
 * Can be bound to the view using [MessageListViewModel.bindView] function.
 *
 * @param cid The full channel id, i.e. "messaging:123"
 * @param chatClient Entry point for all low-level operations.
 * @param clientState Client state of SDK that contains information such as the current user and connection state.
 * such as the current user, connection state, unread counts etc.
 */
@Suppress("TooManyFunctions")
public class MessageListViewModel(
    private val cid: String,
    private val messageId: String? = null,
    private val chatClient: ChatClient = ChatClient.instance(),
    private val clientState: ClientState = chatClient.clientState,
    private val messageListController: MessageListController = MessageListController(
        cid,
        chatClient,
        DeletedMessageVisibility.ALWAYS_VISIBLE,
        true,
        true,
        24L * 60L * 60L * 1000L,
        MessageFooterVisibility.LastInGroup
    ),
) : ViewModel() {

    /**
     * Holds information about the current channel and is actively updated.
     */
    public val channelState: StateFlow<ChannelState?> = messageListController.channelState

    /**
     * Holds information about the abilities the current user
     * is able to exercise in the given channel.
     *
     * e.g. send messages, delete messages, etc...
     * For a full list @see [io.getstream.chat.android.client.models.ChannelCapabilities].
     */
    public val ownCapabilities: LiveData<Set<String>> = messageListController.ownCapabilities.asLiveData()

    /**
     * Contains a list of messages along with additional
     * information about the message list.
     * TODO
     */
    private var messageListData: LiveData<MessageListItemWrapper> = messageListController.messageListState.map {
        it.toMessageListItemWrapper()
    }.asLiveData()

    /**
     * Contains a list of messages along with additional
     * information about the message list. Sets the
     * TODO
     */
    private var threadListData: LiveData<MessageListItemWrapper> = messageListController.threadListState.map {
        it.toMessageListItemWrapper()
    }.asLiveData()

    /**
     * Regulates the visibility of deleted messages.
     */
    private var _deletedMessageVisibility: MutableLiveData<DeletedMessageVisibility> =
        MutableLiveData(DeletedMessageVisibility.ALWAYS_VISIBLE)

    /**
     * Regulates the visibility of deleted messages.
     */
    public val deletedMessageVisibility: LiveData<DeletedMessageVisibility> = _deletedMessageVisibility

    /**
     * Regulates the message footer visibility.
     */
    private var messageFooterVisibility: MutableLiveData<MessageFooterVisibility> =
        MutableLiveData(MessageFooterVisibility.WithTimeDifference())

    /**
     * Represents the current state of the message list
     * that is a product of multiple sources.
     */
    private val stateMerger = MediatorLiveData<State>()

    /**
     * Current message list state.
     * @see State
     */
    public val state: LiveData<State> = stateMerger

    /**
     * Whether the user is viewing a thread.
     * @see Mode
     */
    public val mode: LiveData<Mode> = messageListController.mode.map {
        val mode = when (it) {
            is MessageMode.MessageThread -> Mode.Thread(it.parentMessage, it.threadState)
            MessageMode.Normal -> Mode.Normal
        }
        println("mode is: ${mode::class.java.simpleName}")
        mode
    }.asLiveData()

    /**
     * Emits true if we should load more messages.
     */
    private val _loadMoreLiveData = MediatorLiveData<Boolean>()

    /**
     * Emits true if we should load more messages.
     */
    public val loadMoreLiveData: LiveData<Boolean> = _loadMoreLiveData

    /**
     *  The current channel used to load the message list data.
     */
    public val channel: LiveData<Channel> = messageListController.channel.asLiveData().distinctUntilChanged()

    /**
     * The target message that the list should scroll to.
     * Used when scrolling to a pinned message, a message opened from
     * a push notification or similar.
     */
    private val _targetMessage: MutableLiveData<Message> = MutableLiveData()

    /**
     * The target message that the list should scroll to.
     * Used when scrolling to a pinned message, a message opened from
     * a push notification or similar.
     */
    public val targetMessage: LiveData<Message> = _targetMessage

    /**
     * Emits error events.
     */
    private val _errorEvents: MutableLiveData<EventWrapper<ErrorEvent>> = MutableLiveData()

    /**
     * Emits error events.
     */
    public val errorEvents: LiveData<EventWrapper<ErrorEvent>> = _errorEvents

    /**
     * The currently logged in user.
     */
    public val user: LiveData<User?> = clientState.user.asLiveData()

    /**
     * The logger used to print to errors, warnings, information
     * and other things to log.
     */
    private val logger: TaggedLogger = StreamLog.getLogger("Chat:MessageListViewModel")

    /**
     * Evaluates whether date separators should be added to the message list.
     */
    private var dateSeparatorHandler: DateSeparatorHandler? =
        DateSeparatorHandler { previousMessage: Message?, message: Message ->
            if (previousMessage == null) {
                true
            } else {
                (message.getCreatedAtOrThrow().time - previousMessage.getCreatedAtOrThrow().time) > SEPARATOR_TIME
            }
        }

    /**
     * Evaluates whether thread separators should be added to the message list.
     */
    private var threadDateSeparatorHandler: DateSeparatorHandler? =
        DateSeparatorHandler { previousMessage: Message?, message: Message ->
            if (previousMessage == null) {
                false
            } else {
                (message.getCreatedAtOrThrow().time - previousMessage.getCreatedAtOrThrow().time) > SEPARATOR_TIME
            }
        }

    /**
     * Determines the position of a message inside a group.
     */
    private var messagePositionHandler: MessagePositionHandler = MessagePositionHandler.defaultHandler()

    /**
     * A background job used for view model initialization.
     * The job should be canceled after receiving the first, non-null value from the watch channel request.
     */
    private var initialJob: Job? = null

    /**
     * Emits the status of searching situation. True when inside a search and false otherwise.
     */
    private val _insideSearch = MediatorLiveData<Boolean>()

    /**
     * Emits the status of searching situation. True when inside a search and false otherwise.
     */
    public val insideSearch: LiveData<Boolean> = _insideSearch

    init {
        stateMerger.addSource(MutableLiveData(State.Loading)) { stateMerger.value = it }

        initialJob = viewModelScope.launch {
            channelState.collect { channelState ->
                if (channelState != null) {
                    initWithOfflinePlugin(channelState)
                    initialJob?.cancel()
                }
            }
        }
    }

    /**
     * Initializes the ViewModel with offline capabilities using
     * [io.getstream.chat.android.offline.plugin.internal.OfflinePlugin] after connecting the user.
     *
     * @param channelState State container for particular channel.
     */
    private fun initWithOfflinePlugin(channelState: ChannelState) {
        _loadMoreLiveData.addSource(channelState.loadingOlderMessages.asLiveData()) { _loadMoreLiveData.value = it }
        _insideSearch.addSource(channelState.insideSearch.asLiveData()) { _insideSearch.value = it }

        stateMerger.apply {
            addSource(messageListData) {
                value = State.Result(it)
            }
        }

        messageId.takeUnless { it.isNullOrBlank() }?.let { targetMessageId ->
            stateMerger.observeForever(object : Observer<State> {
                override fun onChanged(state: State?) {
                    if (state is State.Result) {
                        onEvent(Event.ShowMessage(targetMessageId))
                        stateMerger.removeObserver(this)
                    }
                }
            })
        }
    }

    /**
     * Handles an [event] coming from the View layer.
     * @see Event
     */
    @Suppress("LongMethod", "ComplexMethod")
    public fun onEvent(event: Event) {
        when (event) {
            is Event.EndRegionReached -> {
                onEndRegionReached()
            }

            is Event.BottomEndRegionReached -> {
                onBottomEndRegionReached(event.messageId)
            }

            is Event.LastMessageRead -> {
                messageListController.markLastMessageRead()
            }
            is Event.ThreadModeEntered -> {
                onThreadModeEntered(event.parentMessage)
            }
            is Event.BackButtonPressed -> {
                onBackButtonPressed()
            }
            is Event.DeleteMessage -> {
                messageListController.deleteMessage(event.message, event.hard)
            }
            is Event.FlagMessage -> {
                messageListController.flagMessage(event.message) { result ->
                    event.resultHandler(result)
                    if (result.isError) {
                        _errorEvents.postValue(EventWrapper(ErrorEvent.FlagMessageError(result.error())))
                    }
                }
            }
            is Event.PinMessage -> {
                messageListController.pinMessage(event.message) {
                    if (it.isError) {
                        _errorEvents.postValue(EventWrapper(ErrorEvent.PinMessageError(it.error())))
                    }
                }
            }
            is Event.UnpinMessage -> {
                messageListController.unpinMessage(event.message) {
                    if (it.isError) {
                        _errorEvents.postValue(EventWrapper(ErrorEvent.UnpinMessageError(it.error())))
                    }
                }
            }
            is Event.GiphyActionSelected -> {
                onGiphyActionSelected(event)
            }
            is Event.RetryMessage -> {
                messageListController.resendMessage(event.message)
            }
            is Event.MessageReaction -> {
                onMessageReaction(event.message, event.reactionType, event.enforceUnique)
            }
            is Event.MuteUser -> {
                messageListController.muteUser(event.user) {
                    if (it.isError) {
                        _errorEvents.postValue(EventWrapper(ErrorEvent.MuteUserError(it.error())))
                    }
                }
            }
            is Event.UnmuteUser -> {
                messageListController.unmuteUser(event.user) {
                    if (it.isError) {
                        _errorEvents.postValue(EventWrapper(ErrorEvent.UnmuteUserError(it.error())))
                    }
                }
            }
            is Event.BlockUser -> {
                val channelClient = chatClient.channel(cid)
                channelClient.shadowBanUser(
                    targetId = event.user.id,
                    reason = null,
                    timeout = null,
                ).enqueue(
                    onError = { chatError ->
                        logger.e { "Could not block user: ${chatError.message}" }
                        _errorEvents.postValue(EventWrapper(ErrorEvent.BlockUserError(chatError)))
                    }
                )
            }
            is Event.ReplyMessage -> {
                chatClient.setMessageForReply(event.cid, event.repliedMessage).enqueue(
                    onError = { chatError ->
                        logger.e {
                            "Could not reply message: ${chatError.message}. " +
                                "Cause: ${chatError.cause?.message}"
                        }
                    }
                )
            }
            is Event.DownloadAttachment -> {
                event.downloadAttachmentCall().enqueue(
                    onError = { chatError ->
                        logger.e {
                            "Attachment download error: ${chatError.message}. " +
                                "Cause: ${chatError.cause?.message}"
                        }
                    }
                )
            }
            is Event.ShowMessage -> {
                val message = getMessageWithId(event.messageId)

                if (message != null) {
                    _targetMessage.value = message
                } else {
                    chatClient.loadMessageById(
                        cid,
                        event.messageId
                    ).enqueue { result ->
                        if (result.isSuccess) {
                            _targetMessage.value = result.data()
                        } else {
                            val error = result.error()
                            logger.e { "Could not load message: ${error.message}. Cause: ${error.cause?.message}" }
                        }
                    }
                }
            }
            is Event.RemoveAttachment -> {
                val attachmentToBeDeleted = event.attachment
                chatClient.loadMessageById(
                    cid,
                    event.messageId
                ).enqueue { result ->
                    if (result.isSuccess) {
                        val message = result.data()
                        message.attachments.removeAll { attachment ->
                            if (attachmentToBeDeleted.assetUrl != null) {
                                attachment.assetUrl == attachmentToBeDeleted.assetUrl
                            } else {
                                attachment.imageUrl == attachmentToBeDeleted.imageUrl
                            }
                        }

                        chatClient.updateMessage(message).enqueue(
                            onError = { chatError ->
                                logger.e {
                                    "Could not edit message to remove its attachments: ${chatError.message}. " +
                                        "Cause: ${chatError.cause?.message}"
                                }
                            }
                        )
                    } else {
                        logger.e { "Could not load message: ${result.error()}" }
                    }
                }
            }
            is Event.ReplyAttachment -> {
                val messageId = event.repliedMessageId
                val cid = event.cid
                chatClient.loadMessageById(
                    cid,
                    messageId
                ).enqueue { result ->
                    if (result.isSuccess) {
                        val message = result.data()
                        onEvent(Event.ReplyMessage(cid, message))
                    } else {
                        val error = result.error()
                        logger.e { "Could not load message to reply: ${error.message}. Cause: ${error.cause?.message}" }
                    }
                }
            }
        }
    }

    /**
     * Returns a message with the given ID from the [messageListData].
     *
     * @param messageId The ID of the selected message.
     * @return The [Message] with the ID, if it exists.
     */
    public fun getMessageWithId(messageId: String): Message? = messageListController.getMessageWithId(messageId)

    /**
     * When the user clicks the scroll to bottom button we need to take the user to the bottom of the newest
     * messages. If the messages are not loaded we need to load them first and then scroll to the bottom of the
     * list.
     */
    public fun scrollToBottom(scrollToBottom: () -> Unit): Unit = messageListController
        .scrollToBottom(DEFAULT_MESSAGES_LIMIT, scrollToBottom)

    /**
     * Sets the date separator handler which determines when to add date separators.
     * By default, a date separator will be added if the difference between two messages' dates is greater than 4h.
     *
     * @param dateSeparatorHandler The handler to use. If null, [messageListData] won't contain date separators.
     */
    public fun setDateSeparatorHandler(dateSeparatorHandler: DateSeparatorHandler?) {
        this.dateSeparatorHandler = dateSeparatorHandler
    }

    /**
     * Sets thread date separator handler which determines when to add date separators inside the thread.
     * @see setDateSeparatorHandler
     *
     * @param threadDateSeparatorHandler The handler to use. If null, [messageListData] won't contain date separators.
     */
    public fun setThreadDateSeparatorHandler(threadDateSeparatorHandler: DateSeparatorHandler?) {
        this.threadDateSeparatorHandler = threadDateSeparatorHandler
    }

    /**
     * Sets a handler which determines the position of a message inside a group.
     *
     * @param messagePositionHandler The handler to use.
     */
    public fun setMessagePositionHandler(messagePositionHandler: MessagePositionHandler) {
        this.messagePositionHandler = messagePositionHandler
    }

    /**
     * Handles the send, shuffle and cancel Giphy actions.
     *
     * @param event The type of action the user has selected.
     */
    private fun onGiphyActionSelected(event: Event.GiphyActionSelected) {
        val action = when (event.action) {
            GiphyAction.SEND -> SendGiphyCommon(event.message)
            GiphyAction.SHUFFLE -> ShuffleGiphyCommon(event.message)
            GiphyAction.CANCEL -> CancelGiphyCommon(event.message)
        }
        messageListController.performGiphyAction(action)
    }

    /**
     * Loads more messages if we have reached
     * the oldest message currently loaded.
     */
    private fun onEndRegionReached() {
        messageListController.loadOlderMessages()
    }

    /**
     * Loads more messages if we have reached the newest messages currently loaded and we are handling search.
     */
    private fun onBottomEndRegionReached(baseMessageId: String?) {
        if (baseMessageId != null) {
            messageListController.loadNewerMessages(baseMessageId, DEFAULT_MESSAGES_LIMIT)
        } else {
            logger.e { "There's no base message to request more message at bottom of limit" }
        }
    }

    /**
     * Evaluates whether a navigation event should occur
     * or if we should switch from thread mode back to
     * normal mode.
     */
    private fun onBackButtonPressed() {
        mode.value?.run {
            when (this) {
                is Mode.Normal -> {
                    stateMerger.postValue(State.NavigateUp)
                }
                is Mode.Thread -> {
                    onNormalModeEntered()
                }
            }
        }
    }

    /**
     * Handles an event to move to thread mode.
     *
     * @param parentMessage The message with the thread we want to observe.
     */
    private fun onThreadModeEntered(parentMessage: Message) {
        messageListController.enterThreadMode(parentMessage)
        stateMerger.apply {
            removeSource(messageListData)
            addSource(threadListData) {
                value = State.Result(it)
            }
        }
    }

    /**
     * Handles reacting to messages while taking into account if unique reactions are enforced.
     *
     * @param message The message the user is reacting to.
     * @param reactionType The exact reaction type.
     * @param enforceUnique Whether the user is able to leave multiple reactions.
     */
    private fun onMessageReaction(message: Message, reactionType: String, enforceUnique: Boolean) {
        val reaction = Reaction().apply {
            messageId = message.id
            type = reactionType
            score = 1
        }
        messageListController.reactToMessage(reaction, message, enforceUnique)
    }

    /**
     * Called when upon initialization or exiting thread mode.
     */
    private fun onNormalModeEntered() {
        messageListController.enterNormalMode()
        stateMerger.apply {
            removeSource(threadListData)
            addSource(messageListData) {
                value = State.Result(it)
            }
        }
    }

    /**
     * Sets the value used to filter deleted messages.
     * @see DeletedMessageVisibility
     *
     * @param deletedMessageVisibility Changes the visibility of deleted messages.
     */
    public fun setDeletedMessageVisibility(deletedMessageVisibility: DeletedMessageVisibility) {
        this._deletedMessageVisibility.value = deletedMessageVisibility
    }

    /**
     * Sets the value used to determine if message footer content is shown.
     * @see MessageFooterVisibility
     *
     * @param messageFooterVisibility Changes the visibility of message footers.
     */
    public fun setMessageFooterVisibility(messageFooterVisibility: MessageFooterVisibility) {
        this.messageFooterVisibility.value = messageFooterVisibility
    }

    /**
     * The current state of the message list.
     */
    public sealed class State {

        /**
         * Signifies that the message list is loading.
         */
        public object Loading : State()

        /**
         * Signifies that the messages have successfully loaded.
         *
         * @param messageListItem Contains the requested messages along with additional information.
         */
        public data class Result(val messageListItem: MessageListItemWrapper) : State()

        /**
         * Signals that the View should navigate back.
         */
        public object NavigateUp : State()
    }

    /**
     * Represents events coming from the View class.
     */
    public sealed class Event {

        /**
         * When the back button is pressed.
         */
        public object BackButtonPressed : Event()

        /**
         * When the oldest loaded message in the list has been reached.
         */
        public object EndRegionReached : Event()

        /**
         * When the newest loaded message in the list has been reached and there's still newer messages to be loaded.
         */
        public data class BottomEndRegionReached(val messageId: String?) : Event()

        /**
         * When the newest message in the channel has been read.
         */
        public object LastMessageRead : Event()

        /**
         * When the users enters thread mode.
         *
         * @param parentMessage The original message the thread was spun off from.
         */
        public data class ThreadModeEntered(val parentMessage: Message) : Event()

        /**
         * When the user deletes a message.
         *
         * @param message The message to be deleted.
         * @param hard Determines whether the message will be soft or hard deleted.
         *
         * Soft delete - Deletes the message on the client side but it remains available
         * via server-side export functions.
         * Hard delete - message is deleted everywhere.
         */
        public data class DeleteMessage(val message: Message, val hard: Boolean = false) : Event()

        /**
         * When the user flags a message.
         *
         * @param message The message to be flagged.
         * @param resultHandler Lambda function that handles the result of the operation.
         * e.g. if the message was successfully flagged or not.
         */
        public data class FlagMessage(val message: Message, val resultHandler: ((Result<Flag>) -> Unit) = { }) : Event()

        /**
         * When the user pins a message.
         *
         * @param message The message to be pinned.
         */
        public data class PinMessage(val message: Message) : Event()

        /**
         * When the user unpins a message.
         *
         * @param message The message to be unpinned.
         */
        public data class UnpinMessage(val message: Message) : Event()

        /**
         * When the user selects a Giphy message.
         * e.g. send, shuffle or cancel.
         *
         * @param message The Giphy message.
         * @param action The Giphy action. e.g. send, shuffle or cancel.
         */
        public data class GiphyActionSelected(val message: Message, val action: GiphyAction) : Event()

        /**
         * Retry sending a message that has failed to send.
         *
         * @param message The message that will be re-sent.
         */
        public data class RetryMessage(val message: Message) : Event()

        /**
         * When the user leaves a reaction to a message.
         *
         * @param message The message the user is reacting to
         * @param reactionType The reaction type.
         * @param enforceUnique Whether the user is able to leave multiple reactions.
         */
        public data class MessageReaction(
            val message: Message,
            val reactionType: String,
            val enforceUnique: Boolean,
        ) : Event()

        /**
         * When the user mutes a user.
         *
         * @param user The user to be muted.
         */
        public data class MuteUser(val user: User) : Event()

        /**
         * When the user unmutes a user.
         *
         * @param user The user to be unmuted.
         */
        public data class UnmuteUser(val user: User) : Event()

        /**
         * When the user blocks another user.
         *
         * @param user The user to be blocked.
         * @param cid The full channel id, i.e. "messaging:123".
         */
        public data class BlockUser(val user: User, val cid: String) : Event()

        /**
         * When the user replies to a message.
         *
         * @param cid The full channel id, i.e. "messaging:123".
         * @param repliedMessage The message the user is replying to.
         */
        public data class ReplyMessage(val cid: String, val repliedMessage: Message) : Event()

        /**
         * When the user is replying to a single attachment.
         * Usually triggered when replying from gallery.
         *
         * @param cid The full channel id, i.e. "messaging:123".
         * @param repliedMessageId The message the user is replying to.
         */
        public data class ReplyAttachment(val cid: String, val repliedMessageId: String) : Event()

        /**
         * When the user downloads an attachment.
         *
         * @param downloadAttachmentCall A handler for downloading that returns a [Call]
         * with the option of asynchronous operation.
         */
        public data class DownloadAttachment(val downloadAttachmentCall: () -> Call<Unit>) : Event()

        /**
         * When we need to display a particular message to the user.
         * Usually triggered by clicking on pinned messages or navigation
         * to the message list via push notifications.
         *
         * @param messageId The id of the message we need to navigate to.
         */
        public data class ShowMessage(val messageId: String) : Event()

        /**
         * When the user removes an attachment from a message that was previously sent.
         *
         * @param messageId The message from which an attachment will be deleted.
         * @param attachment The attachment to be deleted.
         */
        public data class RemoveAttachment(val messageId: String, val attachment: Attachment) : Event()
    }

    /**
     * The modes the message list can be in.
     */
    public sealed class Mode {

        /**
         * Thread mode. Occurs when a user enters a thread.
         *
         * @param parentMessage The original message all messages in a thread are replying to.
         * @param threadState Contains information about the state of the thread, such as
         * if we are loading older messages, have reached the oldest message available, etc.
         */
        public data class Thread(val parentMessage: Message, val threadState: ThreadState? = null) : Mode()

        /**
         * Normal mode. When the user is not participating in a thread.
         */
        public object Normal : Mode()
    }

    /**
     * A class designed for error event propagation.
     *
     * @param chatError Contains the original [Throwable] along with a message.
     */
    public sealed class ErrorEvent(public open val chatError: ChatError) {

        /**
         * When an error occurs while muting a user.
         *
         * @param chatError Contains the original [Throwable] along with a message.
         */
        public data class MuteUserError(override val chatError: ChatError) : ErrorEvent(chatError)

        /**
         * When an error occurs while unmuting a user.
         *
         * @param chatError Contains the original [Throwable] along with a message.
         */
        public data class UnmuteUserError(override val chatError: ChatError) : ErrorEvent(chatError)

        /**
         * When an error occurs while flagging a message.
         *
         * @param chatError Contains the original [Throwable] along with a message.
         */
        public data class FlagMessageError(override val chatError: ChatError) : ErrorEvent(chatError)

        /**
         * When an error occurs while blocking a user.
         *
         * @param chatError Contains the original [Throwable] along with a message.
         */
        public data class BlockUserError(override val chatError: ChatError) : ErrorEvent(chatError)

        /**
         * When an error occurs while pinning a message.
         *
         * @param chatError Contains the original [Throwable] along with a message.
         */
        public data class PinMessageError(override val chatError: ChatError) : ErrorEvent(chatError)

        /**
         * When an error occurs while unpinning a message.
         *
         * @param chatError Contains the original [Throwable] along with a message.
         */
        public data class UnpinMessageError(override val chatError: ChatError) : ErrorEvent(chatError)
    }

    /**
     * A SAM designed to evaluate if a date separator should be added between messages.
     */
    public fun interface DateSeparatorHandler {
        public fun shouldAddDateSeparator(previousMessage: Message?, message: Message): Boolean
    }

    /**
     * A handler to determine the position of a message inside a group.
     */
    public fun interface MessagePositionHandler {
        /**
         * Determines the position of a message inside a group.
         *
         * @param prevMessage The previous [Message] in the list.
         * @param message The current [Message] in the list.
         * @param nextMessage The next [Message] in the list.
         * @param isAfterDateSeparator If a date separator was added before the current [Message].
         *
         * @return The position of the current message inside the group.
         */
        public fun handleMessagePosition(
            prevMessage: Message?,
            message: Message,
            nextMessage: Message?,
            isAfterDateSeparator: Boolean,
        ): List<MessageListItem.Position>

        public companion object {
            /**
             * The default implementation of the [MessagePositionHandler] interface which can be taken
             * as a reference when implementing a custom one.
             *
             * @return The default implementation of [MessagePositionHandler].
             */
            internal fun defaultHandler(): MessagePositionHandler {
                return MessagePositionHandler { prevMessage: Message?, message: Message, nextMessage: Message?, isAfterDateSeparator: Boolean ->
                    val prevUser = prevMessage?.user
                    val user = message.user
                    val nextUser = nextMessage?.user

                    fun Message.isServerMessage(): Boolean {
                        return type == ModelType.message_system || type == ModelType.message_error
                    }

                    mutableListOf<MessageListItem.Position>().apply {
                        if (prevMessage == null || prevUser != user || prevMessage.isServerMessage() || isAfterDateSeparator) {
                            add(MessageListItem.Position.TOP)
                        }
                        if (prevMessage != null && nextMessage != null && prevUser == user && nextUser == user) {
                            add(MessageListItem.Position.MIDDLE)
                        }
                        if (nextMessage == null || nextUser != user || nextMessage.isServerMessage()) {
                            add(MessageListItem.Position.BOTTOM)
                        }
                    }
                }
            }
        }
    }

    internal companion object {
        /**
         * The default limit of messages to load.
         */
        const val DEFAULT_MESSAGES_LIMIT = 30

        const val SEPARATOR_TIME = 1000 * 60 * 60 * 4
    }
}
