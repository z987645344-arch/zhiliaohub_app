package com.zhiliaohub.app.ui

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal fun networkMessage(error: IOException, automaticRetryCount: Int = 0): String {
    val causes = generateSequence(error as Throwable?) { it.cause }.toList()
    val reason = when {
        causes.any { it is UnknownHostException } ->
            "无法解析服务器地址。"
        causes.any { it is SocketTimeoutException } ->
            "连接服务器超时。"
        causes.any { it is SSLException } ->
            "HTTPS 安全连接失败，请检查证书、系统时间和服务器地址。"
        causes.any { it is ConnectException } ->
            "无法连接服务器。"
        causes.any { it.hasConnectionResetMessage() } ->
            "连接被网络或代理线路重置。"
        causes.any { it is SocketException } ->
            "网络连接已中断。"
        else -> error.message?.takeIf { it.contains("已阻止请求") }
            ?: "网络请求失败。"
    }
    if (causes.any { it is SSLException } || reason.contains("已阻止请求")) return reason
    val advice = if (automaticRetryCount > 0) {
        "App 已自动重试${automaticRetryCount}次；如仍失败，请检查服务器地址、DNS、网络或代理线路。"
    } else {
        "请检查服务器地址、DNS、网络或代理线路后手动重试。"
    }
    return "$reason$advice"
}

private fun Throwable.hasConnectionResetMessage(): Boolean {
    val messageText = message?.lowercase() ?: return false
    return RESET_MARKERS.any(messageText::contains)
}

private val RESET_MARKERS = listOf(
    "connection reset",
    "reset by peer",
    "connection aborted",
    "software caused connection abort",
    "broken pipe",
)
