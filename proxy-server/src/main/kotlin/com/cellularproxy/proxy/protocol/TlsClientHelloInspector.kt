package com.cellularproxy.proxy.protocol

import java.io.PushbackInputStream

sealed interface TlsClientHelloInspectionResult {
    data object NotTls : TlsClientHelloInspectionResult

    data object UnsupportedTls : TlsClientHelloInspectionResult

    data class ClientHello(
        val serverName: String,
        val bytes: ByteArray,
    ) : TlsClientHelloInspectionResult {
        override fun equals(other: Any?): Boolean = other is ClientHello &&
            serverName == other.serverName &&
            bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * serverName.hashCode() + bytes.contentHashCode()
    }
}

object TlsClientHelloInspector {
    fun inspect(input: PushbackInputStream): TlsClientHelloInspectionResult {
        val first = input.read()
        if (first == END_OF_STREAM) {
            return TlsClientHelloInspectionResult.NotTls
        }
        if (first != TLS_HANDSHAKE_CONTENT_TYPE) {
            input.unread(first)
            return TlsClientHelloInspectionResult.NotTls
        }

        val header = ByteArray(TLS_RECORD_HEADER_BYTES)
        header[0] = first.toByte()
        if (!input.readFullyOrUnread(header, alreadyRead = 1)) {
            return TlsClientHelloInspectionResult.UnsupportedTls
        }
        if (header[1].toUnsignedInt() != TLS_MAJOR_VERSION) {
            input.unread(header)
            return TlsClientHelloInspectionResult.NotTls
        }

        val recordLength = header.readUnsignedShort(offset = 3)
        if (recordLength <= 0 || recordLength > MAX_TLS_CLIENT_HELLO_RECORD_BYTES) {
            input.unread(header)
            return TlsClientHelloInspectionResult.UnsupportedTls
        }

        val payload = ByteArray(recordLength)
        if (!input.readFullyOrUnread(payload, alreadyRead = 0, prefixToUnread = header)) {
            return TlsClientHelloInspectionResult.UnsupportedTls
        }

        val serverName =
            parseServerName(payload)
                ?: run {
                    input.unread(payload)
                    input.unread(header)
                    return TlsClientHelloInspectionResult.UnsupportedTls
                }
        return TlsClientHelloInspectionResult.ClientHello(
            serverName = serverName,
            bytes = header + payload,
        )
    }

    private fun parseServerName(payload: ByteArray): String? {
        var offset = 0
        if (payload.getOrNull(offset)?.toUnsignedInt() != TLS_CLIENT_HELLO_HANDSHAKE_TYPE) {
            return null
        }
        offset += 1
        val handshakeLength = payload.readUInt24OrNull(offset) ?: return null
        offset += 3
        if (handshakeLength > payload.size - offset) {
            return null
        }
        offset += 2 + TLS_RANDOM_BYTES
        val sessionIdLength = payload.getOrNull(offset)?.toUnsignedInt() ?: return null
        offset += 1 + sessionIdLength
        val cipherSuitesLength = payload.readUnsignedShortOrNull(offset) ?: return null
        offset += 2 + cipherSuitesLength
        val compressionMethodsLength = payload.getOrNull(offset)?.toUnsignedInt() ?: return null
        offset += 1 + compressionMethodsLength
        val extensionsLength = payload.readUnsignedShortOrNull(offset) ?: return null
        offset += 2
        val extensionsEnd = offset + extensionsLength
        if (extensionsEnd > payload.size) {
            return null
        }

        while (offset + 4 <= extensionsEnd) {
            val type = payload.readUnsignedShortOrNull(offset) ?: return null
            val length = payload.readUnsignedShortOrNull(offset + 2) ?: return null
            offset += 4
            if (offset + length > extensionsEnd) {
                return null
            }
            if (type == SERVER_NAME_EXTENSION_TYPE) {
                return payload.parseServerNameExtension(offset, length)
            }
            offset += length
        }
        return null
    }

    private fun ByteArray.parseServerNameExtension(
        offset: Int,
        length: Int,
    ): String? {
        var cursor = offset
        val end = offset + length
        val listLength = readUnsignedShortOrNull(cursor) ?: return null
        cursor += 2
        if (cursor + listLength > end) {
            return null
        }
        while (cursor + 3 <= offset + 2 + listLength) {
            val nameType = getOrNull(cursor)?.toUnsignedInt() ?: return null
            val nameLength = readUnsignedShortOrNull(cursor + 1) ?: return null
            cursor += 3
            if (cursor + nameLength > offset + 2 + listLength) {
                return null
            }
            if (nameType == HOST_NAME_SERVER_NAME_TYPE) {
                return copyOfRange(cursor, cursor + nameLength)
                    .toString(Charsets.US_ASCII)
                    .takeIf(::isValidServerName)
            }
            cursor += nameLength
        }
        return null
    }
}

private fun PushbackInputStream.readFullyOrUnread(
    target: ByteArray,
    alreadyRead: Int,
    prefixToUnread: ByteArray = ByteArray(0),
): Boolean {
    var offset = alreadyRead
    while (offset < target.size) {
        val read = read(target, offset, target.size - offset)
        if (read == END_OF_STREAM) {
            unread(target, 0, offset)
            if (prefixToUnread.isNotEmpty()) {
                unread(prefixToUnread)
            }
            return false
        }
        offset += read
    }
    return true
}

private fun ByteArray.readUnsignedShort(offset: Int): Int = (this[offset].toUnsignedInt() shl 8) or this[offset + 1].toUnsignedInt()

private fun ByteArray.readUnsignedShortOrNull(offset: Int): Int? = if (offset + 1 < size) {
    readUnsignedShort(offset)
} else {
    null
}

private fun ByteArray.readUInt24OrNull(offset: Int): Int? = if (offset + 2 < size) {
    (this[offset].toUnsignedInt() shl 16) or
        (this[offset + 1].toUnsignedInt() shl 8) or
        this[offset + 2].toUnsignedInt()
} else {
    null
}

private fun Byte.toUnsignedInt(): Int = toInt() and 0xff

private fun isValidServerName(value: String): Boolean = value.isNotBlank() &&
    value.length <= MAX_SERVER_NAME_LENGTH &&
    value.none { it.isWhitespace() || it.code < VISIBLE_ASCII_START || it.code > VISIBLE_ASCII_END }

private const val END_OF_STREAM = -1
private const val TLS_HANDSHAKE_CONTENT_TYPE = 0x16
private const val TLS_MAJOR_VERSION = 0x03
private const val TLS_RECORD_HEADER_BYTES = 5
private const val TLS_CLIENT_HELLO_HANDSHAKE_TYPE = 0x01
private const val TLS_RANDOM_BYTES = 32
private const val SERVER_NAME_EXTENSION_TYPE = 0x0000
private const val HOST_NAME_SERVER_NAME_TYPE = 0
private const val MAX_TLS_CLIENT_HELLO_RECORD_BYTES = 16 * 1024
private const val MAX_SERVER_NAME_LENGTH = 253
private const val VISIBLE_ASCII_START = 0x21
private const val VISIBLE_ASCII_END = 0x7e
