package com.zhiliaohub.app.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ServerAddress(
    val normalized: String,
    val httpUrl: HttpUrl,
) {
    val isCleartext: Boolean = httpUrl.scheme == "http"

    companion object {
        fun parse(raw: String): ServerAddress {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "请输入服务器地址。" }
            val url = trimmed.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("服务器地址格式无效。")
            require(url.scheme == "http" || url.scheme == "https") {
                "服务器地址必须使用 http 或 https。"
            }
            require(url.username.isEmpty() && url.password.isEmpty()) {
                "服务器地址不能包含用户名或密码。"
            }
            require(url.query == null && url.fragment == null) {
                "服务器地址不能包含查询参数或片段。"
            }
            require(url.encodedPath == "/") {
                "请输入服务根地址，不要附加接口路径。"
            }
            val normalized = url.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
            return ServerAddress(normalized = normalized, httpUrl = url)
        }

        fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
            first.scheme == second.scheme && first.host == second.host && first.port == second.port
    }
}

