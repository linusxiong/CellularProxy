package com.cellularproxy.app.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

class ForegroundServiceManifestContractTest {
    @Test
    fun `manifest declares exported launcher activity`() {
        val manifest = parseManifest()

        val launcher = manifest.activities.singleOrNull {
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

        val service = manifest.services.singleOrNull {
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

    private fun parseManifest(): ManifestContract {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance()
            .apply {
                isIgnoringComments = true
                isNamespaceAware = true
            }
            .newDocumentBuilder()
            .parse(manifestFile)

        val root = document.documentElement
        val usesPermissions = root.childElements("uses-permission")
            .mapNotNull { it.androidAttribute("name") }
            .toSet()
        val services = root.childElements("application")
            .single()
            .childElements("service")
            .map { service ->
                ServiceContract(
                    name = service.androidAttribute("name").orEmpty(),
                    exported = service.androidAttribute("exported")?.toBooleanStrictOrNull(),
                    foregroundServiceType = service.androidAttribute("foregroundServiceType"),
                    properties = service.childElements("property")
                        .map { property ->
                            ServicePropertyContract(
                                name = property.androidAttribute("name").orEmpty(),
                                value = property.androidAttribute("value").orEmpty(),
                            )
                        },
                )
            }
        val activities = root.childElements("application")
            .single()
            .childElements("activity")
            .map { activity ->
                ActivityContract(
                    name = activity.androidAttribute("name").orEmpty(),
                    exported = activity.androidAttribute("exported")?.toBooleanStrictOrNull(),
                    intentFilters = activity.childElements("intent-filter")
                        .map { intentFilter ->
                            IntentFilterContract(
                                actions = intentFilter.childElements("action")
                                    .mapNotNull { it.androidAttribute("name") }
                                    .toSet(),
                                categories = intentFilter.childElements("category")
                                    .mapNotNull { it.androidAttribute("name") }
                                    .toSet(),
                            )
                        },
                )
            }

        return ManifestContract(
            usesPermissions = usesPermissions,
            activities = activities,
            services = services,
        )
    }
}

private data class ManifestContract(
    val usesPermissions: Set<String>,
    val activities: List<ActivityContract>,
    val services: List<ServiceContract>,
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

private fun Element.androidAttribute(localName: String): String? =
    getAttributeNS(ANDROID_NAMESPACE, localName).takeIf(String::isNotBlank)

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
