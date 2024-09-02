package org.thoughtcrime.securesms.webrtc

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.ANSWER
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.END_CALL
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.ICE_CANDIDATES
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.OFFER
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.PRE_OFFER
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.PROVISIONAL_ANSWER
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.service.WebRtcCallService
import org.thoughtcrime.securesms.util.CallNotificationBuilder
import org.webrtc.IceCandidate


class CallMessageProcessor(private val context: Context, private val textSecurePreferences: TextSecurePreferences, lifecycle: Lifecycle, private val storage: StorageProtocol) {

    companion object {
        private const val VERY_EXPIRED_TIME = 15 * 60 * 1000L

        fun safeStartService(context: Context, intent: Intent) {
            // If the foreground service crashes then it's possible for one of these intents to
            // be started in the background (in which case 'startService' will throw a
            // 'BackgroundServiceStartNotAllowedException' exception) so catch that case and try
            // to re-start the service in the foreground
            try { context.startService(intent) }
            catch(e: Exception) {
                try { ContextCompat.startForegroundService(context, intent) }
                catch (e2: Exception) {
                    Log.e("Loki", "Unable to start CallMessage intent: ${e2.message}")
                }
            }
        }
    }

    init {
        lifecycle.coroutineScope.launch(IO) {
            while (isActive) {
                val nextMessage = WebRtcUtils.SIGNAL_QUEUE.receive()
                Log.d("Loki", nextMessage.type?.name ?: "CALL MESSAGE RECEIVED")
                val sender = nextMessage.sender ?: continue
                val approvedContact = Recipient.from(context, Address.fromSerialized(sender), false).isApproved
                Log.i("Loki", "Contact is approved?: $approvedContact")
                if (!approvedContact && storage.getUserPublicKey() != sender) continue

                // if the user has not enabled voice/video calls
                if (!textSecurePreferences.isCallNotificationsEnabled()) {
                    Log.d("Loki","Dropping call message if call notifications disabled")
                    if (nextMessage.type != PRE_OFFER) continue
                    val sentTimestamp = nextMessage.sentTimestamp ?: continue
                    if (textSecurePreferences.setShownCallNotification()) {
                        // first time call notification encountered
                        val notification = CallNotificationBuilder.getFirstCallNotification(context, sender)
                        context.getSystemService(NotificationManager::class.java).notify(CallNotificationBuilder.WEBRTC_NOTIFICATION, notification)
                        insertMissedCall(sender, sentTimestamp, isFirstCall = true)
                    } else {
                        insertMissedCall(sender, sentTimestamp)
                    }
                    continue
                }
                // or if the user has not granted audio/microphone permissions
                else if (!Permissions.hasAll(context, Manifest.permission.RECORD_AUDIO)) {
                    if (nextMessage.type != PRE_OFFER) continue
                    val sentTimestamp = nextMessage.sentTimestamp ?: continue
                    Log.d("Loki", "Attempted to receive a call without audio permissions")
                    insertMissedPermissionCall(sender, sentTimestamp)
                    continue
                }

                val isVeryExpired = (nextMessage.sentTimestamp?:0) + VERY_EXPIRED_TIME < SnodeAPI.nowWithOffset
                if (isVeryExpired) {
                    Log.e("Loki", "Dropping very expired call message")
                    continue
                }

                when (nextMessage.type) {
                    OFFER -> incomingCall(nextMessage)
                    ANSWER -> incomingAnswer(nextMessage)
                    END_CALL -> incomingHangup(nextMessage)
                    ICE_CANDIDATES -> handleIceCandidates(nextMessage)
                    PRE_OFFER -> incomingPreOffer(nextMessage)
                    PROVISIONAL_ANSWER, null -> {} // TODO: if necessary
                }
            }
        }
    }

    private fun insertMissedCall(sender: String, sentTimestamp: Long, isFirstCall: Boolean = false) {
        val currentUserPublicKey = storage.getUserPublicKey()
        if (sender == currentUserPublicKey) return // don't insert a "missed" due to call notifications disabled if it's our own sender
        if (isFirstCall) {
            storage.insertCallMessage(sender, CallMessageType.CALL_FIRST_MISSED, sentTimestamp)
        } else {
            storage.insertCallMessage(sender, CallMessageType.CALL_MISSED, sentTimestamp)
        }
    }

    private fun insertMissedPermissionCall(sender: String, sentTimestamp: Long) {
        val currentUserPublicKey = storage.getUserPublicKey()
        if (sender == currentUserPublicKey) return // don't insert a "missed" due to call notifications disabled if it's our own sender
        storage.insertCallMessage(sender, CallMessageType.CALL_MISSED_PERMISSION, sentTimestamp)
    }

    private fun incomingHangup(callMessage: CallMessage) {
        val callId = callMessage.callId ?: return
        val hangupIntent = WebRtcCallService.remoteHangupIntent(context, callId)
        safeStartService(context, hangupIntent)
    }

    private fun incomingAnswer(callMessage: CallMessage) {
        val recipientAddress = callMessage.sender ?: return
        val callId = callMessage.callId ?: return
        val sdp = callMessage.sdps.firstOrNull() ?: return
        val answerIntent = WebRtcCallService.incomingAnswer(
                context = context,
                address = Address.fromSerialized(recipientAddress),
                sdp = sdp,
                callId = callId
        )

        safeStartService(context, answerIntent)
    }

    private fun handleIceCandidates(callMessage: CallMessage) {
        val callId = callMessage.callId ?: return
        val sender = callMessage.sender ?: return

        val iceCandidates = callMessage.iceCandidates()
        if (iceCandidates.isEmpty()) return

        val iceIntent = WebRtcCallService.iceCandidates(
                context = context,
                iceCandidates = iceCandidates,
                callId = callId,
                address = Address.fromSerialized(sender)
        )
        safeStartService(context, iceIntent)
    }

    private fun incomingPreOffer(callMessage: CallMessage) {
        // handle notification state
        val recipientAddress = callMessage.sender ?: return
        val callId = callMessage.callId ?: return
        val incomingIntent = WebRtcCallService.preOffer(
                context = context,
                address = Address.fromSerialized(recipientAddress),
                callId = callId,
                callTime = callMessage.sentTimestamp!!
        )
        safeStartService(context, incomingIntent)
    }

    private fun incomingCall(callMessage: CallMessage) {
        val recipientAddress = callMessage.sender ?: return
        val callId = callMessage.callId ?: return
        val sdp = callMessage.sdps.firstOrNull() ?: return
        val incomingIntent = WebRtcCallService.incomingCall(
                context = context,
                address = Address.fromSerialized(recipientAddress),
                sdp = sdp,
                callId = callId,
                callTime = callMessage.sentTimestamp!!
        )
        safeStartService(context, incomingIntent)
    }

    private fun CallMessage.iceCandidates(): List<IceCandidate> {
        if (sdpMids.size != sdpMLineIndexes.size || sdpMLineIndexes.size != sdps.size) {
            return listOf() // uneven sdp numbers
        }
        val candidateSize = sdpMids.size
        return (0 until candidateSize).map { i ->
            IceCandidate(sdpMids[i], sdpMLineIndexes[i], sdps[i])
        }
    }

}