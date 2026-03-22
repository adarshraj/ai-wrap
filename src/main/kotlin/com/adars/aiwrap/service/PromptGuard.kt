package com.adars.aiwrap.service

import jakarta.ws.rs.BadRequestException
import org.jboss.logging.Logger

/**
 * Detects common prompt-injection patterns in user-supplied text before it is
 * substituted into a prompt template and sent to an LLM.
 *
 * Only call this on the *variable* parts (question, context, documentText), never
 * on the template itself (which is trusted, read from classpath).
 */
object PromptGuard {

    private val log: Logger = Logger.getLogger(PromptGuard::class.java)

    /**
     * Patterns that strongly suggest an attempt to hijack the system prompt.
     * Each entry is a compiled Regex paired with a human-readable label used in logs.
     */
    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        // Classic instruction override
        Regex("""ignore\s+(all\s+)?(previous|prior|above|earlier)\s+(instructions?|prompts?|context|rules?)""", RegexOption.IGNORE_CASE)
                to "ignore-previous-instructions",
        Regex("""disregard\s+(all\s+)?(previous|prior|above|earlier|the)\s""", RegexOption.IGNORE_CASE)
                to "disregard-instructions",
        Regex("""forget\s+(all\s+)?(previous|prior|above|earlier|your|the)\s""", RegexOption.IGNORE_CASE)
                to "forget-instructions",

        // Role / persona hijacking
        Regex("""you\s+are\s+now\b""", RegexOption.IGNORE_CASE)
                to "you-are-now",
        Regex("""act\s+as\s+(a\s+|an\s+)?\w+""", RegexOption.IGNORE_CASE)
                to "act-as",
        Regex("""pretend\s+(to\s+be|you\s+are)\b""", RegexOption.IGNORE_CASE)
                to "pretend-to-be",
        Regex("""roleplay\s+as\b""", RegexOption.IGNORE_CASE)
                to "roleplay-as",

        // Injected role headers (ChatML / OpenAI / raw)
        Regex("""^(system|assistant)\s*:\s*\S""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
                to "role-header-injection",

        // New instruction declaration
        Regex("""new\s+(instructions?|rules?|prompt|task)\s*:""", RegexOption.IGNORE_CASE)
                to "new-instructions",
        Regex("""override\s+(the\s+)?(previous|prior|above|system)\s""", RegexOption.IGNORE_CASE)
                to "override-instructions",

        // Known jailbreak terms
        Regex("""\bjailbreak\b""", RegexOption.IGNORE_CASE)
                to "jailbreak",
        Regex("""\bDAN\s+(mode|prompt)\b""", RegexOption.IGNORE_CASE)
                to "DAN-mode",

        // Template/XML tag injection
        Regex("""<\s*(system|instruction|prompt)\s*>""", RegexOption.IGNORE_CASE)
                to "xml-tag-injection",

        // Special LLM token sequences
        Regex("""\[INST]|\[/INST]""")
                to "llama-instruction-tokens",
        Regex("""<\|im_start\|>|<\|im_end\|>""")
                to "chatml-tokens",
    )

    /**
     * Scans [input] for injection patterns. Throws [BadRequestException] on detection.
     *
     * @param input     The user-supplied string that will fill a template variable.
     * @param fieldName Label used in error and log messages (e.g. "question", "documentText").
     */
    fun assertSafe(input: String, fieldName: String = "input") {
        for ((pattern, label) in PATTERNS) {
            if (pattern.containsMatchIn(input)) {
                log.warnf("Prompt injection attempt detected in field '%s' (pattern: %s)", fieldName, label)
                throw BadRequestException(
                    "Invalid input in '$fieldName': content not permitted in this context (pattern: $label)"
                )
            }
        }
    }
}
