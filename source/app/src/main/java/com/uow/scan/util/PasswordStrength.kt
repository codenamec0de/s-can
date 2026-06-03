package com.uow.scan.util

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Local password-strength engine — runs **100% on-device, no network ever**.
 *
 * Ports the S'CAN V4 design's `estimateStrength`: Shannon entropy from the
 * character pool, minus penalties for common passwords, common-word stems,
 * repeated characters, embedded years, and keyboard/number runs. Produces a
 * level (0..3), a label, an offline-fast-hash crack-time estimate, and a list
 * of human-readable issues.
 */
object PasswordStrength {

    /** Small bundled common-password list (top-N) — matches the design spec. */
    private val COMMON_PW: Set<String> = setOf(
        "password", "123456", "123456789", "12345678", "12345", "1234567", "12345678910",
        "qwerty", "qwerty123", "qwertyuiop", "1q2w3e4r", "1qaz2wsx", "admin", "welcome",
        "abc123", "password1", "password123", "iloveyou", "sunshine", "princess", "dragon",
        "monkey", "letmein", "football", "baseball", "master", "login", "superman", "batman",
        "000000", "111111", "121212", "123123", "654321", "666666", "696969", "7777777",
        "trustno1", "starwars", "whatever", "zaq12wsx", "michael", "jennifer", "jordan",
        "hunter2", "shadow", "ashley", "michelle", "soccer", "hello", "charlie", "freedom",
        "pokemon", "flower", "passw0rd", "p@ssword", "p@ssw0rd", "changeme", "ncc1701",
        "azerty", "google", "test", "guest", "root", "toor", "samsung", "internet",
    )

    private val KEYBOARD_RUNS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890", "qazwsxedc")

    private val REPEAT = Regex("(.)\\1{2,}")
    private val TRAILING_DIGITS = Regex("\\d+$")
    private val YEAR = Regex("(19|20)\\d{2}")

    /** level: 0 Weak · 1 Fair · 2 Good · 3 Strong ; -1 = empty. */
    data class Strength(
        val level: Int,
        val label: String,
        val crackTime: String,
        val entropy: Int,
        val issues: List<String>,
    ) {
        val isEmpty: Boolean get() = level < 0
    }

    private fun log2(n: Double) = ln(n) / ln(2.0)

    /** Largest single unit of a crack-time in seconds (matches the design's fmtCrack). */
    private fun fmtCrack(seconds: Double): String {
        if (seconds < 0.001) return "instantly"
        if (seconds < 1) return "less than a second"
        val units = listOf(
            "century" to 3_155_760_000.0, "year" to 31_557_600.0, "month" to 2_629_800.0,
            "week" to 604_800.0, "day" to 86_400.0, "hour" to 3_600.0, "minute" to 60.0, "second" to 1.0,
        )
        for ((name, secs) in units) {
            if (seconds >= secs) {
                val n = (seconds / secs).toLong()
                if (name == "century" && n > 100) return "centuries"
                return "${"%,d".format(n)} $name${if (n != 1L) "s" else ""}"
            }
        }
        return "instantly"
    }

    fun estimate(pw: String): Strength {
        if (pw.isEmpty()) return Strength(-1, "—", "—", 0, emptyList())
        val length = pw.length
        val lower = pw.any { it in 'a'..'z' }
        val upper = pw.any { it in 'A'..'Z' }
        val digit = pw.any { it in '0'..'9' }
        val symbol = pw.any { !it.isLetterOrDigit() }
        var pool = 0
        if (lower) pool += 26
        if (upper) pool += 26
        if (digit) pool += 10
        if (symbol) pool += 33
        if (pool == 0) pool = 1

        var entropy = length * log2(pool.toDouble())
        val issues = mutableListOf<String>()
        val lc = pw.lowercase()

        if (COMMON_PW.contains(lc)) {
            issues.add("This is one of the most common passwords"); entropy = min(entropy, 8.0)
        }
        val base = lc.replace(TRAILING_DIGITS, "")   // catch "sunshine12" style
        if (base.length >= 4 && COMMON_PW.contains(base)) {
            issues.add("Built on a common word"); entropy = min(entropy, 22.0)
        }
        if (REPEAT.containsMatchIn(pw)) { issues.add("Contains repeated characters"); entropy -= 8 }
        if (YEAR.containsMatchIn(pw)) { issues.add("Contains a year — easy to guess"); entropy -= 6 }

        var walk = false
        for (run in KEYBOARD_RUNS) {
            var i = 0
            while (i + 4 <= run.length) {
                val seg = run.substring(i, i + 4)
                if (lc.contains(seg) || lc.contains(seg.reversed())) { walk = true; break }
                i++
            }
            if (walk) break
        }
        if (walk) { issues.add("Contains a keyboard or number run"); entropy -= 8 }
        if (length < 8) issues.add("Shorter than 8 characters")
        if (!symbol && !digit) issues.add("Add numbers or symbols for variety")

        entropy = max(0.0, entropy)
        // Offline fast-hash attacker: ~1e10 guesses/sec, on average half the space.
        val seconds = 2.0.pow(entropy) / 2 / 1e10
        val crackTime = fmtCrack(seconds)

        val level = when {
            entropy < 30 || COMMON_PW.contains(lc) -> 0
            entropy < 50 -> 1
            entropy < 70 -> 2
            else -> 3
        }
        return Strength(level, LABELS[level], crackTime, entropy.toInt(), issues)
    }

    private val LABELS = listOf("Weak", "Fair", "Good", "Strong")
}
