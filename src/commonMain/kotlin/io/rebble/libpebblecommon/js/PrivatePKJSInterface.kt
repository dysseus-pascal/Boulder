package io.rebble.libpebblecommon.js

interface PrivatePKJSInterface {
    fun privateLog(message: String)
    fun logInterceptedSend()
    fun logInterceptedRequest()
    fun logLocationRequest()
    fun getVersionCode(): Int
    fun getTimelineTokenAsync(): String
}