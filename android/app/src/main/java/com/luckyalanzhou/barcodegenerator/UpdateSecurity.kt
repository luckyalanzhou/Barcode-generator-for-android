package com.luckyalanzhou.barcodegenerator

import java.security.MessageDigest

object UpdateSecurity {
    const val MAX_APK_DOWNLOAD_BYTES = 500L * 1024L * 1024L

    fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun isValidEan13(value: String): Boolean = hasCheckDigit(value, 13)
    fun isValidEan8(value: String): Boolean = hasCheckDigit(value, 8)
    fun isValidUpcA(value: String): Boolean = hasCheckDigit(value, 12)
    fun isValidItf14(value: String): Boolean = hasCheckDigit(value, 14)

    private fun hasCheckDigit(value: String, length: Int): Boolean {
        if (value.length != length || !value.all { it in '0'..'9' }) return false
        val digits = value.map { it - '0' }
        val sum = digits.dropLast(1).reversed().mapIndexed { index, digit -> digit * if (index % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10 == digits.last()
    }

    fun compareVersions(a: String, b: String): Int {
        val left = parseVersion(a) ?: return 0
        val right = parseVersion(b) ?: return 0
        return (0 until maxOf(left.size, right.size)).firstOrNull { (left.getOrElse(it) { 0L }) != (right.getOrElse(it) { 0L }) }
            ?.let { left.getOrElse(it) { 0L }.compareTo(right.getOrElse(it) { 0L }) } ?: 0
    }

    fun parseVersion(value: String): List<Long>? {
        val parts = value.trim().split('.')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.length > 9 || it.toLongOrNull() == null }) return null
        return parts.map(String::toLong)
    }
}
