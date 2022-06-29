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

package io.getstream.chat.android.compose.ui.messages.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.getstream.chat.android.client.models.Message
import io.getstream.chat.android.compose.state.messages.MessagesState
import io.getstream.chat.android.compose.state.messages.MyOwn
import io.getstream.chat.android.compose.state.messages.Other
import io.getstream.chat.android.compose.state.messages.ScrollToPositionState
import io.getstream.chat.android.compose.state.messages.list.MessageFocused
import io.getstream.chat.android.compose.state.messages.list.MessageItemState
import io.getstream.chat.android.compose.state.messages.list.MessageListItemState
import io.getstream.chat.android.compose.ui.components.LoadingIndicator
import io.getstream.chat.android.compose.ui.components.messages.MessagesScrollingOption
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Builds a list of message items, based on the [itemContent] parameter and the state provided within
 * [messagesState]. Also handles the pagination events, by propagating the event to the call site.
 *
 * Finally, it handles the scrolling behavior, such as when a new message arrives, be it ours or from
 * someone else.
 *
 * @param messagesState Current state of messages, like messages to display, if we're loading more
 * and if we've reached the end of the list.
 * @param lazyListState State of the lazy list that represents the list of messages. Useful for controlling the
 * scroll state.
 * @param onMessagesStartReached Handler for pagination, when the user reaches the start of messages.
 * @param onLastVisibleMessageChanged Handler that notifies us when the user scrolls and the last visible message
 * changes.
 * @param onScrolledToBottom Handler when the user reaches the bottom of the list.
 * @param modifier Modifier for styling.
 * @param contentPadding Padding values to be applied to the message list surrounding the content inside.
 * @param helperContent Composable that, by default, represents the helper content featuring scrolling behavior based
 * on the list state.
 * @param loadingMoreContent Composable that represents the loading more content, when we're loading the next page.
 * @param itemContent Composable that represents the item that displays each message.
 */
@Composable
public fun Messages(
    messagesState: MessagesState,
    lazyListState: LazyListState,
    onMessagesStartReached: () -> Unit,
    onMessagesEndReached: (String) -> Unit,
    onScrollToBottom: () -> Unit,
    onLastVisibleMessageChanged: (Message) -> Unit,
    onScrolledToBottom: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 16.dp),
    helperContent: @Composable BoxScope.() -> Unit = {
        DefaultMessagesHelperContent(
            messagesState,
            lazyListState,
            onScrollToBottom
        )
    },
    loadingMoreContent: @Composable () -> Unit = { DefaultMessagesLoadingMoreIndicator() },
    itemContent: @Composable (MessageListItemState) -> Unit,
) {
    val messages = messagesState.messageItems
    val endOfMessages = messagesState.endOfMessages
    val startOfMessages = messagesState.startOfMessages
    val isLoadingMoreNewMessages = messagesState.isLoadingMoreNewMessages
    val isLoadingMoreOldMessages = messagesState.isLoadingMoreOldMessages

    var parentSize = remember { IntSize(0, 0) }
    val density = LocalDensity.current

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val bottomPadding = contentPadding.calculateBottomPadding()
                    val topPadding = contentPadding.calculateTopPadding()

                    val paddingPixels = with(density) {
                        bottomPadding.roundToPx() + topPadding.roundToPx()
                    }

                    parentSize = IntSize(
                        width = it.size.width,
                        height = it.size.height - paddingPixels
                    )
                },
            state = lazyListState,
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Bottom,
            reverseLayout = true,
            contentPadding = contentPadding
        ) {

            if (isLoadingMoreNewMessages) {
                item {
                    loadingMoreContent()
                }
            }

            itemsIndexed(
                messages,
                key = { _, item ->
                    if (item is MessageItemState) item.message.id else item.toString()
                }
            ) { index, item ->
                val messageItemModifier = if (item is MessageItemState && item.focusState == MessageFocused &&
                    messagesState.scrollToPositionState == ScrollToPositionState.SCROLL_TO_FOCUSED_MESSAGE
                ) {
                    Modifier.onGloballyPositioned {
                        messagesState.calculateMessageOffset(parentSize, it.size)
                    }
                } else {
                    Modifier
                }

                Box(modifier = messageItemModifier) {
                    itemContent(item)

                    if (item is MessageItemState) {
                        onLastVisibleMessageChanged(item.message)
                    }

                    if (index == 0 && lazyListState.isScrollInProgress) {
                        onScrolledToBottom()
                    }

                    if (!endOfMessages &&
                        index == messages.lastIndex &&
                        messages.isNotEmpty() &&
                        lazyListState.isScrollInProgress
                    ) {
                        onMessagesStartReached()
                    }

                    val newestMessageItem = (messages.firstOrNull { it is MessageItemState } as? MessageItemState)
                    if (!startOfMessages &&
                        index == 0 &&
                        messages.isNotEmpty() &&
                        lazyListState.isScrollInProgress
                    ) {
                        newestMessageItem?.message?.id?.let(onMessagesEndReached)
                    }
                }
            }

            if (isLoadingMoreOldMessages) {
                item {
                    loadingMoreContent()
                }
            }
        }

        helperContent()
    }
}

/**
 * Represents the default scrolling behavior and UI for [Messages], based on the state of messages and the scroll state.
 *
 * @param messagesState The state of messages, current message list, thread, user and more.
 * @param lazyListState The scrolling state of the list, used to manipulate and trigger scroll events.
 */
@Composable
internal fun BoxScope.DefaultMessagesHelperContent(
    messagesState: MessagesState,
    lazyListState: LazyListState,
    scrollToBottom: () -> Unit,
) {
    val messages = messagesState.messageItems
    val newMessageState = messagesState.newMessageState
    val scrollToStartState = messagesState.scrollToPositionState
    val coroutineScope = rememberCoroutineScope()

    val firstVisibleItemIndex = lazyListState.firstVisibleItemIndex

    val focusedItemIndex = messages.indexOfFirst { it is MessageItemState && it.focusState is MessageFocused }

    val offset = messagesState.focusedMessageOffset.collectAsState()

    LaunchedEffect(newMessageState) {
        when {
            focusedItemIndex == -1 &&
                !lazyListState.isScrollInProgress &&
                newMessageState == Other &&
                messagesState.scrollToPositionState == ScrollToPositionState.IDLE &&
                firstVisibleItemIndex < 3 -> {
                coroutineScope.launch {
                    lazyListState.animateScrollToItem(0)
                }
            }

            focusedItemIndex == -1 &&
                !lazyListState.isScrollInProgress &&
                newMessageState == MyOwn &&
                messagesState.scrollToPositionState == ScrollToPositionState.IDLE -> {
                coroutineScope.launch {
                    if (firstVisibleItemIndex > 5) {
                        lazyListState.scrollToItem(5)
                    }
                    lazyListState.animateScrollToItem(0)
                }
            }
        }
    }

    LaunchedEffect(focusedItemIndex, offset.value) {
        if (focusedItemIndex != -1 &&
            !lazyListState.isScrollInProgress &&
            scrollToStartState == ScrollToPositionState.SCROLL_TO_FOCUSED_MESSAGE
        ) {
            coroutineScope.launch {
                lazyListState.scrollToItem(focusedItemIndex, offset.value ?: 0)
            }
        }
    }

    LaunchedEffect(scrollToStartState) {
        if (scrollToStartState == ScrollToPositionState.SCROLL_TO_BOTTOM) {
            lazyListState.scrollToItem(0)
        }
    }

    // LaunchedEffect(
    //     newMessageState,
    //     firstVisibleItemIndex,
    //     focusedItemIndex,
    //     offset.value,
    //     scrollToStartState
    // ) {
    //     if (focusedItemIndex != -1 && !lazyListState.isScrollInProgress && scrollToStartState == ScrollToPositionState.IDLE) {
    //         coroutineScope.launch {
    //             lazyListState.scrollToItem(focusedItemIndex, offset.value ?: 0)
    //         }
    //     }
    //
    //     if (scrollToStartState == ScrollToPositionState.SCROLLING) {
    //         lazyListState.scrollToItem(0)
    //     }
    //
    //     when {
    //         focusedItemIndex == -1 &&
    //             !lazyListState.isScrollInProgress && newMessageState == Other &&
    //             messagesState.scrollToStartState == ScrollToPositionState.IDLE &&
    //             firstVisibleItemIndex < 3 -> coroutineScope.launch {
    //             lazyListState.animateScrollToItem(0)
    //         }
    //
    //         focusedItemIndex == -1 &&
    //             !lazyListState.isScrollInProgress && newMessageState == MyOwn &&
    //             messagesState.scrollToStartState == ScrollToPositionState.IDLE -> coroutineScope.launch {
    //             if (firstVisibleItemIndex > 5) {
    //                 lazyListState.scrollToItem(5)
    //             }
    //             lazyListState.animateScrollToItem(0)
    //         }
    //     }
    // }

    if (abs(firstVisibleItemIndex) >= 3) {
        MessagesScrollingOption(
            unreadCount = messagesState.unreadCount,
            modifier = Modifier.align(Alignment.BottomEnd),
            onClick = {
                coroutineScope.launch {
                    scrollToBottom()
                }
            }
        )
    }
}

/**
 * The default loading more indicator.
 */
@Composable
internal fun DefaultMessagesLoadingMoreIndicator() {
    LoadingIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(8.dp)
    )
}
