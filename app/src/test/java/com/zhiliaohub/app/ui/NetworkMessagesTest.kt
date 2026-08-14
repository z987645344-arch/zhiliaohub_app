package com.zhiliaohub.app.ui

import com.zhiliaohub.app.network.httpFailureMessage
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetworkMessagesTest {
    @Test
    fun distinguishesDnsTimeoutResetAndTlsFailures() {
        assertTrue(networkMessage(UnknownHostException()).contains("DNS"))
        assertTrue(networkMessage(SocketTimeoutException(), 2).contains("自动重试2次"))
        assertTrue(networkMessage(SocketException("Connection reset by peer"), 2).contains("重置"))
        assertTrue(networkMessage(SocketException("Connection reset by peer"), 0).contains("手动重试"))
        assertTrue(networkMessage(SSLHandshakeException("certificate rejected")).contains("HTTPS"))
    }

    @Test
    fun explainsCommonHttpBusinessFailuresInChinese() {
        assertTrue(httpFailureMessage(401).contains("未授权"))
        assertTrue(httpFailureMessage(429).contains("请求过于频繁"))
        assertTrue(httpFailureMessage(503).contains("服务器暂时异常"))
        assertTrue(httpFailureMessage(429, "rate_limited").contains("服务器说明：rate_limited"))
    }
}
