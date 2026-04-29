package com.cellularproxy.app.service

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForegroundServiceManifestContractTest {
    @Test
    fun `manifest declares exported launcher activity`() {
        val manifest = parseManifest()

        val launcher =
            manifest.activities.singleOrNull {
                it.name == "com.cellularproxy.app.ui.CellularProxyActivity"
            }
        assertNotNull(launcher, "Manifest should declare the CellularProxy launcher activity")
        assertEquals(true, launcher.exported)
        assertTrue(
            launcher.intentFilters.any { filter ->
                filter.actions.contains("android.intent.action.MAIN") &&
                    filter.categories.contains("android.intent.category.LAUNCHER")
            },
            "Launcher activity should be reachable from the Android launcher",
        )
        assertNotNull(
            Class.forName("com.cellularproxy.app.ui.CellularProxyActivity"),
            "Launcher activity class should exist",
        )
    }

    @Test
    fun `manifest declares foreground proxy service with required permissions`() {
        val manifest = parseManifest()

        assertTrue(
            manifest.usesPermissions.contains("android.permission.INTERNET"),
            "Proxy service needs INTERNET permission for outbound proxy traffic",
        )
        assertTrue(
            manifest.usesPermissions.contains("android.permission.ACCESS_NETWORK_STATE"),
            "Network routing needs ACCESS_NETWORK_STATE permission",
        )
        assertTrue(
            manifest.usesPermissions.contains("android.permission.FOREGROUND_SERVICE"),
            "Foreground proxy service needs FOREGROUND_SERVICE permission",
        )
        assertTrue(
            manifest.usesPermissions.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"),
            "Special-use foreground service type needs its matching Android 14+ permission",
        )
        assertTrue(
            manifest.usesPermissions.contains("android.permission.POST_NOTIFICATIONS"),
            "Foreground status notification needs POST_NOTIFICATIONS on Android 13+",
        )

        val service =
            manifest.services.singleOrNull {
                it.name == "com.cellularproxy.app.service.CellularProxyForegroundService"
            }
        assertNotNull(service, "Manifest should declare the CellularProxy foreground service")
        assertEquals(false, service.exported)
        assertEquals("specialUse", service.foregroundServiceType)
        assertTrue(
            service.properties.contains(
                ServicePropertyContract(
                    name = "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
                    value = "continuous_user_visible_http_proxy",
                ),
            ),
            "Special-use foreground service should declare a specific subtype for review and diagnostics",
        )
    }

    @Test
    fun `manifest enables loopback cleartext management traffic and platform back callbacks`() {
        val manifest = parseManifest()

        assertEquals(
            "@xml/network_security_config",
            manifest.application.networkSecurityConfig,
            "App must allow explicit loopback cleartext traffic for the in-process local management API.",
        )
        assertEquals(
            true,
            manifest.application.enableOnBackInvokedCallback,
            "App should opt in to platform back callbacks on Android 13+.",
        )

        val networkSecurityConfig = parseNetworkSecurityConfig()
        assertTrue(
            networkSecurityConfig.cleartextPermittedDomains.contains("127.0.0.1"),
            "Local management API dispatches to 127.0.0.1 and must be cleartext-permitted.",
        )
        assertTrue(
            networkSecurityConfig.cleartextPermittedDomains.contains("localhost"),
            "Loopback cleartext policy should also cover localhost diagnostics.",
        )
        assertEquals(
            emptySet(),
            networkSecurityConfig.baseCleartextPermittedValues,
            "Cleartext must not be enabled globally.",
        )
    }

    private fun parseNetworkSecurityConfig(): NetworkSecurityConfigContract {
        val configFile = File("src/main/res/xml/network_security_config.xml")
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    isIgnoringComments = true
                    isNamespaceAware = true
                }.newDocumentBuilder()
                .parse(configFile)

        val root = document.documentElement
        val cleartextPermittedDomains =
            root
                .childElements("domain-config")
                .filter { domainConfig ->
                    domainConfig.attribute("cleartextTrafficPermitted") == "true"
                }.flatMap { domainConfig ->
                    domainConfig
                        .childElements("domain")
                        .mapNotNull { domain -> domain.textContent.trim().takeIf(String::isNotBlank) }
                }.toSet()
        val baseCleartextPermittedValues =
            root
                .childElements("base-config")
                .mapNotNull { baseConfig -> baseConfig.attribute("cleartextTrafficPermitted") }
                .toSet()

        return NetworkSecurityConfigContract(
            cleartextPermittedDomains = cleartextPermittedDomains,
            baseCleartextPermittedValues = baseCleartextPermittedValues,
        )
    }

    private fun parseManifest(): ManifestContract {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    isIgnoringComments = true
                    isNamespaceAware = true
                }.newDocumentBuilder()
                .parse(manifestFile)

        val root = document.documentElement
        val application = root.childElements("application").single()
        val usesPermissions =
            root
                .childElements("uses-permission")
                .mapNotNull { it.androidAttribute("name") }
                .toSet()
        val services =
            application
                .childElements("service")
                .map { service ->
                    ServiceContract(
                        name = service.androidAttribute("name").orEmpty(),
                        exported = service.androidAttribute("exported")?.toBooleanStrictOrNull(),
                        foregroundServiceType = service.androidAttribute("foregroundServiceType"),
                        properties =
                            service
                                .childElements("property")
                                .map { property ->
                                    ServicePropertyContract(
                                        name = property.androidAttribute("name").orEmpty(),
                                        value = property.androidAttribute("value").orEmpty(),
                                    )
                                },
                    )
                }
        val activities =
            application
                .childElements("activity")
                .map { activity ->
                    ActivityContract(
                        name = activity.androidAttribute("name").orEmpty(),
                        exported = activity.androidAttribute("exported")?.toBooleanStrictOrNull(),
                        intentFilters =
                            activity
                                .childElements("intent-filter")
                                .map { intentFilter ->
                                    IntentFilterContract(
                                        actions =
                                            intentFilter
                                                .childElements("action")
                                                .mapNotNull { it.androidAttribute("name") }
                                                .toSet(),
                                        categories =
                                            intentFilter
                                                .childElements("category")
                                                .mapNotNull { it.androidAttribute("name") }
                                                .toSet(),
                                    )
                                },
                    )
                }

        return ManifestContract(
            usesPermissions = usesPermissions,
            application =
                ApplicationContract(
                    networkSecurityConfig = application.androidAttribute("networkSecurityConfig"),
                    enableOnBackInvokedCallback =
                        application
                            .androidAttribute("enableOnBackInvokedCallback")
                            ?.toBooleanStrictOrNull(),
                ),
            activities = activities,
            services = services,
        )
    }
}

private data class ManifestContract(
    val usesPermissions: Set<String>,
    val application: ApplicationContract,
    val activities: List<ActivityContract>,
    val services: List<ServiceContract>,
)

private data class ApplicationContract(
    val networkSecurityConfig: String?,
    val enableOnBackInvokedCallback: Boolean?,
)

private data class NetworkSecurityConfigContract(
    val cleartextPermittedDomains: Set<String>,
    val baseCleartextPermittedValues: Set<String>,
)

private data class ActivityContract(
    val name: String,
    val exported: Boolean?,
    val intentFilters: List<IntentFilterContract>,
)

private data class IntentFilterContract(
    val actions: Set<String>,
    val categories: Set<String>,
)

private data class ServiceContract(
    val name: String,
    val exported: Boolean?,
    val foregroundServiceType: String?,
    val properties: List<ServicePropertyContract>,
)

private data class ServicePropertyContract(
    val name: String,
    val value: String,
)

private fun Element.childElements(name: String): List<Element> {
    val nodes = childNodes
    return buildList {
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.tagName == name) {
                add(node)
            }
        }
    }
}

private fun Element.androidAttribute(localName: String): String? = getAttributeNS(ANDROID_NAMESPACE, localName).takeIf(String::isNotBlank)

private fun Element.attribute(name: String): String? = getAttribute(name).takeIf(String::isNotBlank)

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
