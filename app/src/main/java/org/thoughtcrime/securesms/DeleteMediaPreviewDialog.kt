package org.thoughtcrime.securesms

import android.content.Context
import network.loki.messenger.R

class DeleteMediaPreviewDialog {
    companion object {
        @JvmStatic
        fun show(context: Context, doDelete: Runnable) {
            context.showSessionDialog {
                iconAttribute(R.attr.dialog_alert_icon)
                title(context.resources.getQuantityString(R.plurals.deleteMessage, 1, 1))
                text(R.string.deleteMessageDescriptionEveryone)
                dangerButton(R.string.delete) { doDelete.run() }
                cancelButton()
            }
        }
    }
}