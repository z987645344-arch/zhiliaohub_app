package com.zhiliaohub.app.network

import com.zhiliaohub.app.ui.BackupStatusCardState
import com.zhiliaohub.app.ui.BackupStatusTone
import com.zhiliaohub.app.ui.toPresentation
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException

class BackupStatusTest {
    @Test
    fun twoOkStatusesParseOnlyStatusAndHintAndIgnoreDiagnostic() = runBlocking {
        var observedMethod = ""
        var observedPath = ""
        val api = fakeApi { request ->
            observedMethod = request.method
            observedPath = request.url.encodedPath
            response(
                request,
                200,
                payload(
                    hubStatus = "ok",
                    hubHint = "知了hub 当前窗口已有归档。",
                    zhitianStatus = "ok",
                    zhitianHint = "知天当前窗口已有归档。",
                    diagnostic = """"diagnostic":{"path":"/private/backup","archiveCount":7}""",
                ),
            )
        }

        val value = success(api.backupStatus())

        assertEquals("GET", observedMethod)
        assertEquals("/api/admin/backup-status", observedPath)
        assertEquals(BackupState.OK, value.zhiliaohub.status)
        assertEquals("知了hub 当前窗口已有归档。", value.zhiliaohub.hint)
        assertEquals(BackupState.OK, value.zhitian.status)
        assertEquals("知天当前窗口已有归档。", value.zhitian.hint)
        assertFalse(value.toString().contains("diagnostic"))
        assertFalse(ProjectBackupStatus::class.java.declaredFields.any { it.name.contains("diagnostic") })
    }

    @Test
    fun oneStaleStatusIsIndependentAndVisuallyDangerous() = runBlocking {
        val api = fakeApi { request ->
            response(request, 200, payload("stale", "知了hub 已超期。", "ok", "知天正常。"))
        }

        val value = success(api.backupStatus())

        assertEquals(BackupState.STALE, value.zhiliaohub.status)
        assertEquals(BackupState.OK, value.zhitian.status)
        assertEquals(BackupStatusTone.DANGER, value.zhiliaohub.status.toPresentation().tone)
        assertEquals(BackupStatusTone.DANGER, BackupState.UNKNOWN.toPresentation().tone)
        assertEquals(BackupStatusTone.LOW_KEY, value.zhitian.status.toPresentation().tone)
        assertEquals(BackupStatusTone.NEUTRAL, BackupState.DISABLED.toPresentation().tone)
    }

    @Test
    fun zhitianUnreachableDoesNotCollapseZhiliaohubStatus() = runBlocking {
        val api = fakeApi { request ->
            response(request, 200, payload("ok", "知了hub 正常。", "unreachable", "无法连接知天。"))
        }

        val value = success(api.backupStatus())

        assertEquals(BackupState.OK, value.zhiliaohub.status)
        assertEquals("知了hub 正常。", value.zhiliaohub.hint)
        assertEquals(BackupState.UNREACHABLE, value.zhitian.status)
        assertEquals(BackupStatusTone.DANGER, value.zhitian.status.toPresentation().tone)
    }

    @Test
    fun networkFailureRetriesAsReadOnlyAndKeepsPreviousRows() = runBlocking {
        var attempts = 0
        val api = fakeApi {
            attempts += 1
            throw SocketException("Connection reset")
        }
        val previous = BackupStatus(
            zhiliaohub = ProjectBackupStatus(BackupState.OK, "知了hub 上次正常。"),
            zhitian = ProjectBackupStatus(BackupState.OK, "知天上次正常。"),
        )
        val previousState = BackupStatusCardState().loaded(previous)

        val result = api.backupStatus()
        val failedState = previousState.unavailable("取不到备份状态")

        assertTrue(result is ApiResult.NetworkFailure)
        assertEquals(2, (result as ApiResult.NetworkFailure).automaticRetryCount)
        assertEquals(3, attempts)
        assertSame(previous, failedState.latest)
        assertEquals("取不到备份状态", failedState.fetchFailure)
    }

    @Test
    fun unauthorizedResponseUsesExistingHttpFailureWithoutRetry() = runBlocking {
        var attempts = 0
        val api = fakeApi { request ->
            attempts += 1
            response(request, 401, """{"error":"session_expired"}""")
        }

        val result = api.backupStatus()

        assertTrue(result is ApiResult.HttpFailure)
        assertEquals(401, (result as ApiResult.HttpFailure).statusCode)
        assertTrue(result.message.contains("未授权"))
        assertEquals(1, attempts)
    }

    private fun fakeApi(responder: (Request) -> Response): ZhiliaohubApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> responder(chain.request()) }
            .build()
        return ZhiliaohubApi(
            address = ServerAddress.parse("https://example.com"),
            client = client,
            retryPolicy = NetworkRetryPolicy(
                retryDelaysMillis = listOf(0L, 0L),
                delayAction = {},
            ),
        )
    }

    private fun response(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
        .build()

    private fun payload(
        hubStatus: String,
        hubHint: String,
        zhitianStatus: String,
        zhitianHint: String,
        diagnostic: String? = null,
    ): String {
        val diagnosticField = diagnostic?.let { ",$it" }.orEmpty()
        return """{"zhiliaohub":{"status":"$hubStatus","hint":"$hubHint"$diagnosticField},"zhitian":{"status":"$zhitianStatus","hint":"$zhitianHint"$diagnosticField}}"""
    }

    private fun success(result: ApiResult<BackupStatus>): BackupStatus {
        assertTrue(result is ApiResult.Success)
        return (result as ApiResult.Success).value
    }
}
