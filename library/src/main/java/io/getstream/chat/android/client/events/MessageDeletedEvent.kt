package io.getstream.chat.android.client.events

import io.getstream.chat.android.client.Message
import io.getstream.chat.android.client.User

class MessageDeletedEvent : RemoteEvent() {
    lateinit var cid: String
    lateinit var message: Message
    lateinit var user: User
}