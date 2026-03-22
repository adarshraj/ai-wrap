package com.adars.aiwrap.service

import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class AuditService {

    private val log: Logger = Logger.getLogger("AUDIT")

    fun record(
        userId: String,
        action: String,
        provider: String,
        template: String?,
        model: String?,
        processingTimeMs: Long,
        success: Boolean,
        errorType: String? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
    ) {
        val entry = buildString {
            append("{")
            append("\"userId\":\"").append(userId.replace("\"", "\\\"")).append("\",")
            append("\"action\":\"").append(action).append("\",")
            append("\"provider\":\"").append(provider).append("\",")
            append("\"template\":\"").append((template ?: "raw").replace("\"", "\\\"")).append("\",")
            append("\"model\":\"").append((model ?: "default").replace("\"", "\\\"")).append("\",")
            append("\"processingTimeMs\":").append(processingTimeMs).append(",")
            append("\"success\":").append(success).append(",")
            append("\"errorType\":\"").append((errorType ?: "").replace("\"", "\\\"")).append("\"")
            if (inputTokens != null) append(",\"inputTokens\":").append(inputTokens)
            if (outputTokens != null) append(",\"outputTokens\":").append(outputTokens)
            append("}")
        }
        log.info(entry)
    }
}
