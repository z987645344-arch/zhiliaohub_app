package com.zhiliaohub.app.ui

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal fun networkMessage(error: IOException): String = when (error) {
    is UnknownHostException -> "无法解析服务器地址，请检查地址、网络和 DNS。"
    is ConnectException -> "无法连接服务器，请确认后台已启动且手机能够访问该地址。"
    is SocketTimeoutException -> "连接服务器超时，请检查网络后重试。"
    is SSLException -> "HTTPS 安全连接失败，请检查证书和服务器地址。"
    else -> error.message?.takeIf { it.contains("已阻止请求") }
        ?: "网络请求失败，请检查服务器地址和网络连接。"
}

