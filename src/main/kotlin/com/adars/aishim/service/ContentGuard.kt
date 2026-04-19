package com.adars.aishim.service

import jakarta.ws.rs.BadRequestException
import org.jboss.logging.Logger

/**
 * Detects harmful or dangerous intent in the final prompt before it is sent to any LLM.
 *
 * This is distinct from [PromptGuard] (which stops template injection).
 * ContentGuard runs on the fully assembled prompt — whether the caller wrote it directly
 * or it was built from a template — and blocks requests that ask the AI to assist with
 * dangerous, destructive, or illegal activities.
 *
 * Design principle: patterns require *intent context* (e.g. "how to make" + "bomb"),
 * not isolated keywords, to keep false-positive rates low for legitimate apps.
 */
object ContentGuard {

    private val log: Logger = Logger.getLogger(ContentGuard::class.java)

    private val PATTERNS: List<Pair<Regex, String>> = listOf(

        // ── Weapons & explosives ──────────────────────────────────────────────
        Regex(
            """(how\s+to\s+|steps?\s+to\s+|guide\s+(to|for)\s+|help\s+me\s+|teach\s+me\s+to\s+)?
               (make|build|create|craft|manufacture|assemble|synthesize|produce)\s+
               (a\s+|an\s+)?(bomb|explosive|grenade|landmine|ied|pipe\s*bomb|
                weapon|firearm|gun|rifle|pistol|silencer|suppressor|
                poison|nerve\s+agent|sarin|vx\s+gas|chemical\s+weapon|
                bioweapon|biological\s+weapon|anthrax|ricin)""".trimIndent(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
        ) to "weapons-or-explosives",

        Regex(
            """(acquire|buy|obtain|source|get)\s+(illegal\s+)?(weapons?|firearms?|guns?|explosives?|ammo|ammunition)\s+
               (without|illegally|bypassing|avoiding)""".trimIndent(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
        ) to "illegal-weapons-acquisition",

        // ── Malware & cyberattacks ────────────────────────────────────────────
        Regex(
            """(write|create|build|develop|code|generate|make)\s+(a\s+|an\s+)?
               (malware|ransomware|virus|worm|trojan|keylogger|rootkit|
                spyware|botnet|rat\b|remote\s+access\s+trojan|
                ddos\s+tool|exploit|zero.?day\s+exploit|payload)""".trimIndent(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
        ) to "malware-creation",

        Regex(
            """(how\s+to\s+|help\s+me\s+)?(hack|compromise|breach|infiltrate|break\s+into|gain\s+(unauthorized\s+)?access\s+(to|into))\s+
               (a\s+|an\s+|the\s+)?(server|database|account|system|network|website|application)""".trimIndent(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
        ) to "unauthorized-system-access",

        // ── Data & system destruction ─────────────────────────────────────────
        Regex(
            """(delete|drop|truncate|wipe|destroy|erase|purge)\s+
               (the\s+|all\s+|entire\s+|every\s+)?
               (database|databases|all\s+tables|all\s+data|production\s+data|
                all\s+records|all\s+users|everything\s+in\s+the\s+db)""".trimIndent(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
        ) to "data-destruction",

        Regex(
            """(run|execute|send)\s+(this\s+)?(sql|command|script|query)\s+.*
               (drop\s+table|drop\s+database|delete\s+from|truncate\s+table)""".trimIndent(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS, RegexOption.DOT_MATCHES_ALL)
        ) to "destructive-sql-command",

        // ── Violence against people ───────────────────────────────────────────
        Regex(
            """(how\s+to\s+|steps?\s+to\s+|best\s+way\s+to\s+|help\s+me\s+)
               (kill|murder|assassinate|poison|hurt|injure|physically\s+harm|attack)\s+
               (a\s+|an\s+|my\s+|the\s+|someone|a\s+person|people|humans?)""".trimIndent(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
        ) to "violence-against-persons",

        // ── Drug synthesis ────────────────────────────────────────────────────
        Regex(
            """(how\s+to\s+|steps?\s+to\s+|synthesize\s+|manufacture\s+|make\s+|produce\s+)
               (meth(amphetamine)?|heroin|fentanyl|lsd|mdma|cocaine|crack\s+cocaine|
                crystal\s+meth|illegal\s+drugs?)""".trimIndent(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
        ) to "drug-synthesis",

        // ── Child safety ──────────────────────────────────────────────────────
        Regex(
            """(generate|create|write|describe|produce)\s+(sexual|explicit|nude|naked)\s+
               (content|image|story|text|material)\s+(of|about|involving|featuring|with)\s+
               (a\s+)?(child|minor|kid|underage|teen\s+under)""".trimIndent(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
        ) to "child-safety",

        // ── Identity theft & fraud ────────────────────────────────────────────
        Regex(
            """(how\s+to\s+|help\s+me\s+|steps?\s+to\s+)
               (steal|clone|forge|fake|spoof)\s+
               (someone.?s\s+)?(identity|credit\s+card|passport|social\s+security|
                bank\s+account|credentials|personal\s+data)""".trimIndent(),
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
        ) to "identity-theft-or-fraud",
    )

    /**
     * Scans [prompt] for harmful intent patterns.
     * Throws [BadRequestException] with a generic message on detection (no pattern detail leaked to caller).
     * Always logs the specific pattern for the operator.
     */
    fun assertSafe(prompt: String) {
        for ((pattern, label) in PATTERNS) {
            if (pattern.containsMatchIn(prompt)) {
                log.warnf("Harmful content detected in prompt (pattern: %s). First 200 chars: %.200s",
                    label, prompt)
                throw BadRequestException(
                    "Request blocked: the prompt contains content that cannot be processed."
                )
            }
        }
    }
}
