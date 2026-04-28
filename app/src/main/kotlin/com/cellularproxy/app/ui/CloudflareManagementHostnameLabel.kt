package com.cellularproxy.app.ui

import java.net.URI

internal fun String.safeCloudflareManagementHostnameLabel(): String {
    val value = lineSequence().firstOrNull().orEmpty()
    if ("://" !in value) {
        return value.safeSchemeLessCloudflareHostnameLabel()
    }
    return runCatching {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme == null || host == null) {
            value.safeUrlLikeCloudflareHostnameLabel()
        } else {
            URI(scheme, null, host, uri.port, null, null, null).toString()
        }
    }.getOrElse { value.safeUrlLikeCloudflareHostnameLabel() }
}

private fun String.safeSchemeLessCloudflareHostnameLabel(): String = substringBefore('/')
    .substringBefore('\\')
    .substringBefore('?')
    .substringBefore('#')
    .substringAfterLast('@')

private fun String.safeUrlLikeCloudflareHostnameLabel(): String {
    val scheme = substringBefore("://").lowercase()
    val authority =
        substringAfter("://")
            .substringBefore('/')
            .substringBefore('\\')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
    return if (scheme.isBlank()) {
        authority
    } else if (authority.isBlank()) {
        "$scheme://"
    } else {
        "$scheme://$authority"
    }
}
