package top.steins.autologin.domain.rule

/**
 * 用于更新检查的语义版本号，仅接受 MAJOR.MINOR.PATCH[-prerelease][+build]。
 */
internal class SemanticVersion private constructor(
    private val major: Int,
    private val minor: Int,
    private val patch: Int,
    private val prerelease: List<String>?
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }

        return when {
            prerelease == null && other.prerelease == null -> 0
            prerelease == null -> 1
            other.prerelease == null -> -1
            else -> comparePrerelease(prerelease, other.prerelease)
        }
    }

    private fun comparePrerelease(first: List<String>, second: List<String>): Int {
        val commonSize = minOf(first.size, second.size)
        repeat(commonSize) { index ->
            val firstPart = first[index]
            val secondPart = second[index]
            val firstNumber = firstPart.toIntOrNull()
            val secondNumber = secondPart.toIntOrNull()
            val comparison = when {
                firstNumber != null && secondNumber != null -> firstNumber.compareTo(secondNumber)
                firstNumber != null -> -1
                secondNumber != null -> 1
                else -> firstPart.compareTo(secondPart)
            }
            if (comparison != 0) return comparison
        }
        return first.size.compareTo(second.size)
    }

    companion object {
        private val versionPattern = Regex(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"""
        )

        fun parseOrNull(value: String): SemanticVersion? {
            val match = versionPattern.matchEntire(value.trim().removePrefix("v")) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            val prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')

            if (prerelease?.any { part ->
                    part.all(Char::isDigit) && part.length > 1 && part.startsWith('0')
                } == true
            ) {
                return null
            }
            return SemanticVersion(major, minor, patch, prerelease)
        }

        fun isNewer(candidate: String, current: String): Boolean {
            val candidateVersion = parseOrNull(candidate) ?: return false
            val currentVersion = parseOrNull(current) ?: return false
            return candidateVersion > currentVersion
        }
    }
}
