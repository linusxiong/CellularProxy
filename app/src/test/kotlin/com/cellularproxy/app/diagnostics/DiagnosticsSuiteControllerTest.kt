package com.cellularproxy.app.diagnostics

import com.cellularproxy.shared.logging.LogRedactionSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DiagnosticsSuiteControllerTest {
    @Test
    fun `run check exposes running state measures duration and records redacted latest result`() {
        var now = 1_000_000_000L
        lateinit var controller: DiagnosticsSuiteController
        controller =
            DiagnosticsSuiteController(
                checks =
                    mapOf(
                        DiagnosticCheckType.PublicIp to
                            DiagnosticCheck {
                                val runningResult =
                                    controller.resultModel(
                                        LogRedactionSecrets(managementApiToken = "management-secret"),
                                    )
                                val publicIp = runningResult.results.single { it.type == DiagnosticCheckType.PublicIp }
                                assertEquals(DiagnosticResultStatus.Running, publicIp.status)
                                assertTrue(controller.isRunning(DiagnosticCheckType.PublicIp))

                                now += 37.milliseconds.inWholeNanoseconds
                                DiagnosticCheckResult(
                                    status = DiagnosticResultStatus.Passed,
                                    details = "IP 203.0.113.9 token=management-secret",
                                )
                            },
                    ),
                nanoTime = { now },
            )

        val record = controller.run(DiagnosticCheckType.PublicIp)

        assertEquals(DiagnosticCheckType.PublicIp, record.type)
        assertEquals(DiagnosticResultStatus.Passed, record.status)
        assertEquals(37.milliseconds, record.duration)
        assertFalse(controller.isRunning(DiagnosticCheckType.PublicIp))

        val result =
            controller
                .resultModel(LogRedactionSecrets(managementApiToken = "management-secret"))
                .results
                .single { it.type == DiagnosticCheckType.PublicIp }
        assertEquals(DiagnosticResultStatus.Passed, result.status)
        assertEquals(37, result.durationMillis)
        assertEquals("IP 203.0.113.9 token=[REDACTED]", result.details)
        assertFalse(result.details.orEmpty().contains("management-secret"))
    }

    @Test
    fun `missing check records failed diagnostic`() {
        val controller = DiagnosticsSuiteController(checks = emptyMap(), nanoTime = { 0L })

        val record = controller.run(DiagnosticCheckType.CloudflareTunnel)

        assertEquals(DiagnosticCheckType.CloudflareTunnel, record.type)
        assertEquals(DiagnosticResultStatus.Failed, record.status)
        assertEquals("missing-check", record.errorCategory)
        assertEquals("No diagnostic check registered for Cloudflare tunnel", record.details)
        assertFalse(controller.isRunning(DiagnosticCheckType.CloudflareTunnel))
    }

    @Test
    fun `thrown exception records failed diagnostic`() {
        var now = 5_000_000_000L
        val controller =
            DiagnosticsSuiteController(
                checks =
                    mapOf(
                        DiagnosticCheckType.LocalManagementApi to
                            DiagnosticCheck {
                                now += 12.milliseconds.inWholeNanoseconds
                                error("management-token failed")
                            },
                    ),
                nanoTime = { now },
            )

        val record = controller.run(DiagnosticCheckType.LocalManagementApi)

        assertEquals(DiagnosticResultStatus.Failed, record.status)
        assertEquals(12.milliseconds, record.duration)
        assertEquals("IllegalStateException", record.errorCategory)

        val result =
            controller
                .resultModel(LogRedactionSecrets(managementApiToken = "management-token"))
                .results
                .single { it.type == DiagnosticCheckType.LocalManagementApi }
        assertEquals("management-token failed".replace("management-token", "[REDACTED]"), result.details)
        assertFalse(result.details.orEmpty().contains("management-token"))
    }

    @Test
    fun `latest record wins when check is rerun`() {
        var now = 0L
        var firstRun = true
        val controller =
            DiagnosticsSuiteController(
                checks =
                    mapOf(
                        DiagnosticCheckType.ProxyBind to
                            DiagnosticCheck {
                                now += 1.milliseconds.inWholeNanoseconds
                                if (firstRun) {
                                    firstRun = false
                                    DiagnosticCheckResult(
                                        status = DiagnosticResultStatus.Failed,
                                        errorCategory = "bind-failed",
                                        details = "old failure",
                                    )
                                } else {
                                    DiagnosticCheckResult(
                                        status = DiagnosticResultStatus.Passed,
                                        details = "bound",
                                    )
                                }
                            },
                    ),
                nanoTime = { now },
            )

        controller.run(DiagnosticCheckType.ProxyBind)
        controller.run(DiagnosticCheckType.ProxyBind)

        val result = controller.resultModel().results.single { it.type == DiagnosticCheckType.ProxyBind }
        assertEquals(DiagnosticResultStatus.Passed, result.status)
        assertEquals(null, result.errorCategory)
        assertEquals("bound", result.details)
    }
}
