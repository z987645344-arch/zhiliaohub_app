package com.zhiliaohub.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class ZhiliaohubApi internal constructor(
    private val address: ServerAddress,
    private val client: OkHttpClient,
    private val retryPolicy: NetworkRetryPolicy = NetworkRetryPolicy(),
) {
    suspend fun checkSession(): ApiResult<Unit> = execute(
        Request.Builder().url(url("/api/admin/device")).get().build(),
        ApiOperation.CHECK_SESSION,
    ) { Unit }

    suspend fun pairDevice(
        pairingCode: String,
        deviceName: String,
        publicKeyPem: String,
    ): ApiResult<Unit> {
        val payload = JSONObject()
            .put("pairingCode", pairingCode)
            .put("deviceName", deviceName)
            .put("publicKeyPem", publicKeyPem)
        return execute(
            jsonPost("/api/device-auth/pair", payload),
            ApiOperation.PAIR_DEVICE,
        ) { body ->
            val root = JSONObject(body)
            require(root.has("device")) { "配对响应缺少设备信息。" }
            Unit
        }
    }

    suspend fun requestChallenge(): ApiResult<Challenge> = execute(
        jsonPost("/api/device-auth/challenge", JSONObject()),
        ApiOperation.REQUEST_CHALLENGE,
    ) { body ->
        val root = JSONObject(body)
        val signatureAlgorithm = root.getString("signatureAlgorithm")
        val signatureEncoding = root.getString("signatureEncoding")
        require(signatureAlgorithm == "SHA256withECDSA") {
            "服务器返回了不支持的签名算法。"
        }
        require(signatureEncoding == "DER_BASE64") {
            "服务器返回了不支持的签名编码。"
        }
        Challenge(
            challengeId = root.getString("challengeId"),
            signedPayload = root.getString("signedPayload"),
            expiresAt = root.getString("expiresAt"),
        )
    }

    suspend fun login(challengeId: String, signatureBase64: String): ApiResult<Unit> {
        val payload = JSONObject()
            .put("challengeId", challengeId)
            .put("signature", signatureBase64)
        return execute(
            jsonPost("/api/device-auth/login", payload),
            ApiOperation.LOGIN,
        ) { body ->
            val root = JSONObject(body)
            require(root.optBoolean("authenticated", false)) { "服务器未确认登录成功。" }
            Unit
        }
    }

    suspend fun health(): ApiResult<HealthStatus> = execute(
        Request.Builder().url(url("/health")).get().build(),
        ApiOperation.HEALTH,
    ) { body ->
        val root = JSONObject(body)
        val status = root.optString("status", "unknown")
        HealthStatus(isOnline = status == "ok", serverStatus = status)
    }

    private fun jsonPost(path: String, payload: JSONObject): Request = Request.Builder()
        .url(url(path))
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

    private fun url(path: String) = address.httpUrl.newBuilder()
        .encodedPath(path)
        .query(null)
        .fragment(null)
        .build()

    private suspend fun <T> execute(
        request: Request,
        operation: ApiOperation,
        parser: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        retryPolicy.execute(operation.requestSafety) {
            executeOnce(request, parser)
        }
    }

    private fun <T> executeOnce(
        request: Request,
        parser: (String) -> T,
    ): ApiResult<T> = try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    ApiResult.HttpFailure(
                        statusCode = response.code,
                        message = serverError(body, response.code),
                    )
                } else try {
                    ApiResult.Success(parser(body))
                } catch (error: Exception) {
                    ApiResult.ProtocolFailure("服务器响应格式与约定不一致。", error)
                }
            }
        } catch (error: IOException) {
            ApiResult.NetworkFailure(error)
        }

    private fun serverError(body: String, statusCode: Int): String {
        val serverDetail = try {
            JSONObject(body).optString("error").takeIf(String::isNotBlank)
        } catch (_: Exception) {
            null
        }
        return httpFailureMessage(statusCode, serverDetail)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun httpFailureMessage(statusCode: Int, serverDetail: String? = null): String {
    val summary = when (statusCode) {
        400 -> "请求内容不符合服务器要求。"
        401 -> "未授权或登录会话已经失效。"
        403 -> "当前账号或设备没有执行此操作的权限。"
        404 -> "服务器上不存在请求的接口或资源。"
        409 -> "请求与服务器当前状态冲突。"
        429 -> "请求过于频繁，服务器已临时限流，请稍后重试。"
        in 500..599 -> "服务器暂时异常，请稍后重试。"
        else -> "服务器返回 HTTP $statusCode。"
    }
    return serverDetail?.let { "$summary 服务器说明：$it" } ?: summary
}
