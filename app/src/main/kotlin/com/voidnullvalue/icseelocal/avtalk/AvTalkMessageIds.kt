package com.voidnullvalue.icseelocal.avtalk

/** DVRIP message IDs used by the working three-connection AVTalk flow from issue #6 main.py. */
object AvTalkMessageIds {
    const val KEEPALIVE = 1006
    const val DECODER_QUERY = 1360
    const val DECODER_RESPONSE = 1361
    const val OPMONITOR_REQUEST = 1413
    const val OPMONITOR_RESPONSE = 1414
    const val CONTROL_REQUEST = 1415
    const val CONTROL_RESPONSE = 1416
    const val CLAIM_REQUEST = 1417
    const val CLAIM_RESPONSE = 1418
    const val MEDIA_UPSTREAM = 1419
}
