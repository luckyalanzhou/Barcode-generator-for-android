package com.luckyalanzhou.barcodegenerator

import com.google.zxing.BarcodeFormat

data class BarcodeValidationResult(val valid: Boolean, val message: String = "")

object BarcodeValidator {
    fun validate(value: String, format: String): BarcodeValidationResult {
        if (value.isEmpty()) return BarcodeValidationResult(false, "内容不能为空")
        return when (format) {
            "EAN-13" -> BarcodeValidationResult(UpdateSecurity.isValidEan13(value), "EAN-13 校验位或格式错误")
            "EAN-8" -> BarcodeValidationResult(UpdateSecurity.isValidEan8(value), "EAN-8 校验位或格式错误")
            "UPC-A" -> BarcodeValidationResult(UpdateSecurity.isValidUpcA(value), "UPC-A 校验位或格式错误")
            "ITF-14" -> BarcodeValidationResult(UpdateSecurity.isValidItf14(value), "ITF-14 校验位或格式错误")
            "Code 39" -> BarcodeValidationResult(value.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%" }, "Code 39 包含非法字符")
            else -> BarcodeValidationResult(true)
        }
    }
}
