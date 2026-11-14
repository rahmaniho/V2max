package ir.v2max.app.ui

import ir.v2max.app.data.Proto

/** Validation for the editable subset, before either saving or cloning a profile. */
internal object EditorValidation {
    data class Fields(val port: Int, val network: String, val security: String)
    sealed interface Result {
        data class Valid(val fields: Fields) : Result
        data class Invalid(val message: String) : Result
    }

    fun validate(address: String, port: String, network: String, security: String, proto: Proto): Result {
        if (address.isBlank()) return Result.Invalid("آدرس سرور را وارد کنید")
        val parsedPort = port.trim().toIntOrNull()
        if (parsedPort == null || parsedPort !in 1..65535) {
            return Result.Invalid("پورت باید عددی بین ۱ و ۶۵۵۳۵ باشد")
        }
        // Hysteria2 always uses its own transport and TLS in ConfigBuilder.
        if (proto == Proto.HYSTERIA2) return Result.Valid(Fields(parsedPort, "hysteria", "tls"))
        val normalizedNetwork = when (val value = network.trim().lowercase()) {
            "" -> "tcp"
            "websocket" -> "ws"
            else -> value
        }
        if (normalizedNetwork !in setOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp", "splithttp", "kcp", "mkcp", "hysteria")) {
            return Result.Invalid("این نوع انتقال در نسخهٔ فعلی هسته پشتیبانی نمی‌شود؛ نوع انتقال را بررسی کنید")
        }
        val normalizedSecurity = security.trim().lowercase()
        if (normalizedSecurity !in setOf("", "none", "tls", "reality")) {
            return Result.Invalid("نوع امنیت باید tls، reality یا none باشد")
        }
        return Result.Valid(Fields(parsedPort, normalizedNetwork, normalizedSecurity))
    }
}
