package com.cellularproxy.shared.logging

data class LogRedactionSecrets(
    val managementApiToken: String? = null,
    val proxyCredential: String? = null,
    val cloudflareTunnelToken: String? = null,
)

object LogRedactor {
    private val sensitiveHeaderLineRegex = Regex(
        pattern = """(?im)^(\s*(?:Authorization|Proxy-Authorization|Cookie|Set-Cookie)\s*:\s*).*$""",
    )
    private val sensitiveHeaderFieldRegex = Regex(
        pattern = """(?i)((?:"(?:Authorization|Proxy-Authorization|Cookie|Set-Cookie)")|(?:\b(?:Authorization|Proxy-Authorization|Cookie|Set-Cookie)\b))(\s*[:=]\s*)("[^"\r\n]*"|[^\r\n,}\]]*)""",
    )
    private val absoluteUrlQueryRegex = Regex(
        pattern = """\b(https?://[^\s?#"'<>),\]}]+)\?[^\s#"'<>),\]}]*(#[^\s"'<>),\]}]*)?""",
    )
    private val relativePathQueryRegex = Regex(
        pattern = """(?<![A-Za-z0-9:/])(/[^\s?#"'<>),\]}]*)\?[^\s#"'<>),\]}]*(#[^\s"'<>),\]}]*)?""",
    )

    fun redact(message: String, secrets: LogRedactionSecrets = LogRedactionSecrets()): String {
        val structurallyRedacted = message
            .replace(sensitiveHeaderFieldRegex) { match ->
                val key = match.groupValues[1]
                val separator = match.groupValues[2]
                val value = match.groupValues[3]
                val redactedValue = if (value.startsWith("\"") && value.endsWith("\"")) {
                    "\"$REDACTED_VALUE\""
                } else {
                    REDACTED_VALUE
                }
                "$key$separator$redactedValue"
            }
            .replace(sensitiveHeaderLineRegex, "$1$REDACTED_VALUE")
            .redactQueryStrings(absoluteUrlQueryRegex)
            .redactQueryStrings(relativePathQueryRegex)

        return secrets.nonBlankValues()
            .fold(structurallyRedacted) { current, secret -> current.replace(secret, REDACTED_VALUE) }
    }

    private fun String.redactQueryStrings(regex: Regex): String = replace(regex) { match ->
        val pathWithoutQuery = match.groupValues[1]
        val fragment = match.groupValues.getOrElse(2) { "" }
        "$pathWithoutQuery?$REDACTED_VALUE$fragment"
    }

    private fun LogRedactionSecrets.nonBlankValues(): List<String> = listOfNotNull(
        managementApiToken,
        proxyCredential,
        cloudflareTunnelToken,
    )
        .filter(String::isNotBlank)
        .distinct()
        .sortedByDescending(String::length)

    private const val REDACTED_VALUE = "[REDACTED]"
}
