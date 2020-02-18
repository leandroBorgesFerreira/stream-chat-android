package io.getstream.chat.android.client.notifications

import com.google.firebase.messaging.RemoteMessage
import io.getstream.chat.android.client.events.ChatEvent

interface ChatNotifications {

    fun setFirebaseToken(firebaseToken: String)

    fun onReceiveFirebaseMessage(remoteMessage: RemoteMessage)

    fun onReceiveWebSocketEvent(event: ChatEvent)
}