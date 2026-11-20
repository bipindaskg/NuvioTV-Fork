package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.AuthDiagnosticReportApi
import com.nuvio.tv.data.remote.dto.AuthDiagnosticAppDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticDeviceDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticEnvironmentDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticFlowDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticReportRequestDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticReportResponseDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticTerminalDto
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.File
import java.nio.file.Files

class AuthDiagnosticReportRepositoryTest {
    private val dir = Files.createTempDirectory("auth-diagnostics-").toFile()
    private val moshi = Moshi.Builder().build()
    private val api = mockk<AuthDiagnosticReportApi>()

    private fun report(attempt: String) = AuthDiagnosticReportRequestDto(
        schemaVersion = 1, startedAtMs = 1, endedAtMs = 2, elapsedMs = 1,
        app = AuthDiagnosticAppDto("com.nuvio.bipindas", "1.0", 1, false),
        device = AuthDiagnosticDeviceDto("", "", "", "", "", 30, emptyList()),
        environment = AuthDiagnosticEnvironmentDto("", null, "", null, null, false),
        flow = AuthDiagnosticFlowDto("startup", attempt, null),
        terminal = AuthDiagnosticTerminalDto("success", "completed", null, null, null),
        timeline = emptyList(), exceptions = emptyList(), rawLogs = emptyList()
    )

    private fun repository(queueFile: File, endpointConfigured: Boolean) = AuthDiagnosticReportRepository(
        authDiagnosticReportApi = api,
        queue = AuthDiagnosticReportQueue(queueFile, moshi),
        endpointConfigured = endpointConfigured
    )

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `without a report endpoint nothing is queued and an old queue is dropped`() = runBlocking {
        val queueFile = File(dir, "auth_diagnostics_queue.jsonl")
        AuthDiagnosticReportQueue(queueFile, moshi).enqueue(report("old"))
        assertTrue(queueFile.exists())

        val result = repository(queueFile, endpointConfigured = false).submit(report("new"))

        assertTrue(result.isFailure)
        assertFalse(queueFile.exists())
        coVerify(exactly = 0) { api.createAuthDiagnosticReport(any()) }
    }

    @Test
    fun `a queue file that cannot be read or written does not throw`() = runBlocking {
        val queueFile = File(dir, "auth_diagnostics_queue.jsonl").apply { mkdirs() }
        coEvery { api.createAuthDiagnosticReport(any()) } returns Response.error(503, "".toResponseBody())

        val failed = repository(queueFile, endpointConfigured = true).submit(report("a"))

        assertTrue(failed.isFailure)
        assertEquals(emptyList<AuthDiagnosticReportRequestDto>(), AuthDiagnosticReportQueue(queueFile, moshi).read())
    }

    @Test
    fun `a failed upload is still queued and sent with the next report`() = runBlocking {
        val queueFile = File(dir, "auth_diagnostics_queue.jsonl")
        val repository = repository(queueFile, endpointConfigured = true)
        coEvery { api.createAuthDiagnosticReport(any()) } returns Response.error(503, "".toResponseBody())
        assertTrue(repository.submit(report("first")).isFailure)
        assertEquals(listOf(report("first")), AuthDiagnosticReportQueue(queueFile, moshi).read())

        coEvery { api.createAuthDiagnosticReport(any()) } returns Response.success(AuthDiagnosticReportResponseDto(reportId = "r1"))
        assertEquals("r1", repository.submit(report("second")).getOrThrow())

        assertFalse(queueFile.exists())
        coVerify(exactly = 2) { api.createAuthDiagnosticReport(report("first")) }
        coVerify(exactly = 1) { api.createAuthDiagnosticReport(report("second")) }
    }
}
