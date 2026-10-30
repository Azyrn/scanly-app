package com.skeler.scanely.core.actions

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RestaurantMenu
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.skeler.scanely.R

enum class WifiType {
    WPA,
    WEP,
    OPEN
}

sealed class ScanAction {

    @get:StringRes
    abstract val label: Int
    abstract val icon: ImageVector

    /** The scanned value this action carries, shown wherever the action is listed. */
    abstract val detail: String

    data class OpenUrl(val url: String) : ScanAction() {
        override val label: Int = R.string.act_open_link
        override val icon: ImageVector = Icons.Rounded.Link
        override val detail: String = url
    }

    data class CopyText(val text: String, @StringRes val displayLabel: Int = R.string.act_copy) : ScanAction() {
        override val label: Int = displayLabel
        override val icon: ImageVector = Icons.Rounded.ContentCopy
        override val detail: String = text
    }

    data class CallPhone(val number: String) : ScanAction() {
        override val label: Int = R.string.act_call
        override val icon: ImageVector = Icons.Rounded.Call
        override val detail: String = number
    }

    data class SendEmail(
        val email: String,
        val subject: String? = null,
        val body: String? = null
    ) : ScanAction() {
        override val label: Int = R.string.act_send_email
        override val icon: ImageVector = Icons.Rounded.Email
        override val detail: String = email
    }

    data class ConnectWifi(
        val ssid: String,
        val password: String?,
        val type: WifiType
    ) : ScanAction() {
        override val label: Int = R.string.act_connect_wifi
        override val icon: ImageVector = Icons.Rounded.Wifi
        override val detail: String = ssid
    }

    data class SendSms(
        val number: String,
        val message: String? = null
    ) : ScanAction() {
        override val label: Int = R.string.act_send_sms
        override val icon: ImageVector = Icons.Rounded.Sms
        override val detail: String = number
    }

    data class AddContact(
        val name: String?,
        val phone: String?,
        val email: String?,
        val organization: String? = null
    ) : ScanAction() {
        override val label: Int = R.string.act_add_contact
        override val icon: ImageVector = Icons.Rounded.Person
        override val detail: String = name ?: phone ?: email.orEmpty()
    }

    data class ShowRaw(val text: String) : ScanAction() {
        override val label: Int = R.string.act_view_text
        override val icon: ImageVector = Icons.AutoMirrored.Rounded.TextSnippet
        override val detail: String = text
    }

    /** startRaw/endRaw: ML Kit CalendarDateTime, e.g. "20240604T090000Z". */
    data class AddEvent(
        val title: String?,
        val location: String? = null,
        val description: String? = null,
        val startRaw: String? = null,
        val endRaw: String? = null
    ) : ScanAction() {
        override val label: Int = R.string.act_add_event
        override val icon: ImageVector = Icons.Rounded.Event
        override val detail: String = title.orEmpty()
    }

    data class LookupProduct(val barcode: String) : ScanAction() {
        override val label: Int = R.string.act_product_info
        override val icon: ImageVector = Icons.Rounded.RestaurantMenu
        override val detail: String = barcode
    }
}
