package coredevices.indexai.time

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HumanDateTimeParser(
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) {

    private val currentDateTime: LocalDateTime get() = clock.now().toLocalDateTime(timeZone)

    fun parse(input: String): InterpretedDateTime? {
        val normalized = input.trim().lowercase()

        return parseRelative(normalized)
            ?: parseAbsoluteDateTime(normalized)
            ?: parseAbsoluteTime(normalized)
            ?: parseAbsoluteDate(normalized)
    }

    /**
     * Scans a full user message for a date/time expression and extracts it.
     * Returns the parsed result along with the matched substring and its range,
     * or null if no date/time expression is found.
     *
     * Example: "remind me to buy groceries tomorrow at 3pm" -> ParsedDateTimeResult(AbsoluteDateTime(...), "tomorrow at 3pm", 32..48)
     */
    fun parseFromMessage(message: String): ParsedDateTimeResult? {
        val normalized = message.lowercase()

        // Patterns ordered by specificity: most specific (datetime) first, least specific last.
        // This ensures "tomorrow at 3pm" matches as AbsoluteDateTime, not just "tomorrow" as AbsoluteDate.
        val timeExpr = """\d{1,2}(?::\d{2})?\s*(?:a\.?\s*m\.?|p\.?\s*m\.?)"""
        val time24Expr = """\d{1,2}:\d{2}"""
        val dayWordExpr = """today|tomorrow"""
        val dayOfWeekExpr = """(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)"""
        val monthExpr = """(?:january|february|march|april|may|june|july|august|september|october|november|december)"""
        val numberWordsExpr = """two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty"""
        val quantifierExpr = """(?:\d+|a|an|one|$numberWordsExpr|a\s+couple(?:\s+of)?|a\s+few|couple(?:\s+of)?|few|several)"""
        val unitExpr = """(?:seconds?|minutes?|hours?|days?|weeks?|months?|years?)"""

        val patterns = listOf(
            // Date + time combinations
            Regex("""(?:$dayWordExpr)\s+at\s+(?:$timeExpr|$time24Expr)"""),
            Regex("""at\s+(?:$timeExpr|$time24Expr)\s+(?:$dayWordExpr)"""),
            Regex("""(?:next\s+|on\s+)?$dayOfWeekExpr\s+at\s+(?:$timeExpr|$time24Expr)"""),
            Regex("""at\s+(?:$timeExpr|$time24Expr)\s+(?:next\s+|on\s+)?$dayOfWeekExpr"""),
            Regex("""(?:on\s+)?$monthExpr\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?\s+at\s+(?:$timeExpr|$time24Expr)"""),
            Regex("""at\s+(?:$timeExpr|$time24Expr)\s+(?:on\s+)?$monthExpr\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?"""),
            Regex("""\d{1,2}/\d{1,2}\s+at\s+(?:$timeExpr|$time24Expr)"""),
            // Relative durations
            Regex("""(?:in\s+)?half\s+an?\s+hour(?:\s+from\s+now)?"""),
            Regex("""(?:in\s+)?half\s+a\s+day(?:\s+from\s+now)?"""),
            Regex("""in\s+$quantifierExpr\s+$unitExpr"""),
            Regex("""$quantifierExpr\s+$unitExpr\s+from\s+now"""),
            // "at <time>" (standalone)
            Regex("""at\s+(?:$timeExpr|$time24Expr)"""),
            // Date patterns
            Regex("""(?:next|on)\s+$dayOfWeekExpr"""),
            Regex("""(?:on\s+)?$monthExpr\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?"""),
            Regex("""\b\d{1,2}/\d{1,2}\b"""),
            Regex("""\b(?:$dayWordExpr)\b"""),
            Regex("""\b$dayOfWeekExpr\b"""),
            // Bare time (e.g. "3pm")
            Regex("""\b$timeExpr"""),
        )

        for (pattern in patterns) {
            pattern.find(normalized)?.let { match ->
                val candidate = match.value.trim()
                parse(candidate)?.let { result ->
                    // Map back to original message range
                    val originalText = message.substring(match.range).trim()
                    val trimStart = message.indexOf(originalText, match.range.first)
                    return ParsedDateTimeResult(
                        result,
                        originalText,
                        trimStart until trimStart + originalText.length
                    )
                }
            }
        }

        return null
    }

    private fun parseRelative(input: String): InterpretedDateTime.Relative? {
        // Handle "half" patterns first (special cases)
        // Pattern: "in half an hour", "half an hour from now"
        val halfHourPattern = Regex("""(?:in\s+)?half\s+an?\s+hour(?:\s+from\s+now)?""")
        if (halfHourPattern.matches(input)) {
            return InterpretedDateTime.Relative(30.minutes)
        }

        // Pattern: "in half a day", "half a day from now"
        val halfDayPattern = Regex("""(?:in\s+)?half\s+a\s+day(?:\s+from\s+now)?""")
        if (halfDayPattern.matches(input)) {
            return InterpretedDateTime.Relative(12.hours)
        }

        val numberWordsPattern = """two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty"""
        val quantifierPattern = """(\d+|a|an|one|$numberWordsPattern|a\s+couple(?:\s+of)?|a\s+few|couple(?:\s+of)?|few|several)"""
        val unitPattern = """(second|seconds|minute|minutes|hour|hours|day|days|week|weeks|month|months|year|years)"""

        // Pattern: "in X units" e.g. "in 3 hours", "in 30 minutes", "in a couple hours"
        val inPattern = Regex("""in\s+$quantifierPattern\s+$unitPattern""")
        inPattern.find(input)?.let { match ->
            val amount = parseQuantifier(match.groupValues[1]) ?: return null
            val unit = match.groupValues[2]
            return toRelative(amount, unit)
        }

        // Pattern: "X units from now" e.g. "3 hours from now", "a few minutes from now"
        val fromNowPattern = Regex("""$quantifierPattern\s+$unitPattern\s+from\s+now""")
        fromNowPattern.find(input)?.let { match ->
            val amount = parseQuantifier(match.groupValues[1]) ?: return null
            val unit = match.groupValues[2]
            return toRelative(amount, unit)
        }

        // Pattern: "X units" e.g. "5 minutes", "30 seconds", "2 hours" (standalone duration)
        val standaloneDurationPattern = Regex("""^$quantifierPattern\s+$unitPattern$""")
        standaloneDurationPattern.find(input)?.let { match ->
            val amount = parseQuantifier(match.groupValues[1]) ?: return null
            val unit = match.groupValues[2]
            return toRelative(amount, unit)
        }

        return null
    }

    private fun parseQuantifier(quantifier: String): Long? {
        val normalized = quantifier.trim().lowercase()
        return when {
            normalized.matches(Regex("""\d+""")) -> normalized.toLongOrNull()
            normalized == "a" || normalized == "an" || normalized == "one" -> 1L
            normalized.contains("couple") -> 2L
            normalized.contains("few") -> 3L
            normalized == "several" -> 5L
            else -> wordToNumber(normalized)
        }
    }

    private fun wordToNumber(word: String): Long? {
        return when (word) {
            "zero" -> 0L
            "one" -> 1L
            "two" -> 2L
            "three" -> 3L
            "four" -> 4L
            "five" -> 5L
            "six" -> 6L
            "seven" -> 7L
            "eight" -> 8L
            "nine" -> 9L
            "ten" -> 10L
            "eleven" -> 11L
            "twelve" -> 12L
            "thirteen" -> 13L
            "fourteen" -> 14L
            "fifteen" -> 15L
            "sixteen" -> 16L
            "seventeen" -> 17L
            "eighteen" -> 18L
            "nineteen" -> 19L
            "twenty" -> 20L
            "thirty" -> 30L
            "forty" -> 40L
            "fifty" -> 50L
            else -> null
        }
    }

    private fun parseAbsoluteDateTime(input: String): InterpretedDateTime.AbsoluteDateTime? {
        // Try patterns that combine date and time

        // Pattern: "tomorrow at 3pm", "today at 3pm"
        val dayWordTimePattern = Regex("""(today|tomorrow)\s+at\s+(.+)""")
        dayWordTimePattern.find(input)?.let { match ->
            val dayWord = match.groupValues[1]
            val timeStr = match.groupValues[2]
            val date = parseDayWord(dayWord) ?: return null
            val time = parseTimeString(timeStr) ?: return null
            return InterpretedDateTime.AbsoluteDateTime(LocalDateTime(date, time))
        }

        // Pattern: "at 3pm tomorrow", "at 3pm today"
        val timeDayWordPattern = Regex("""at\s+(.+?)\s+(today|tomorrow)""")
        timeDayWordPattern.find(input)?.let { match ->
            val timeStr = match.groupValues[1]
            val dayWord = match.groupValues[2]
            val date = parseDayWord(dayWord) ?: return null
            val time = parseTimeString(timeStr) ?: return null
            return InterpretedDateTime.AbsoluteDateTime(LocalDateTime(date, time))
        }

        // Pattern: "next Monday at 3pm", "on Monday at 3pm"
        val dayOfWeekTimePattern = Regex("""(?:next|on)?\s*(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+at\s+(.+)""")
        dayOfWeekTimePattern.find(input)?.let { match ->
            val dayName = match.groupValues[1]
            val timeStr = match.groupValues[2]
            val date = parseNextDayOfWeek(dayName) ?: return null
            val time = parseTimeString(timeStr) ?: return null
            return InterpretedDateTime.AbsoluteDateTime(LocalDateTime(date, time))
        }

        // Pattern: "at 3pm next Monday", "at 3pm on Monday"
        val timeDayOfWeekPattern = Regex("""at\s+(.+?)\s+(?:next|on)?\s*(monday|tuesday|wednesday|thursday|friday|saturday|sunday)""")
        timeDayOfWeekPattern.find(input)?.let { match ->
            val timeStr = match.groupValues[1]
            val dayName = match.groupValues[2]
            val date = parseNextDayOfWeek(dayName) ?: return null
            val time = parseTimeString(timeStr) ?: return null
            return InterpretedDateTime.AbsoluteDateTime(LocalDateTime(date, time))
        }

        // Pattern: "January 15 at 3pm", "on January 15 at 3pm", "January 15, 2026 at 3pm"
        val monthDayTimePattern = Regex("""(?:on\s+)?(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?\s+at\s+(.+)""")
        monthDayTimePattern.find(input)?.let { match ->
            val monthName = match.groupValues[1]
            val day = match.groupValues[2].toIntOrNull() ?: return null
            val year = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
            val timeStr = match.groupValues[4]
            val date = parseMonthDay(monthName, day, year) ?: return null
            val time = parseTimeString(timeStr) ?: return null
            return InterpretedDateTime.AbsoluteDateTime(LocalDateTime(date, time))
        }

        // Pattern: "at 3pm on January 15", "at 3pm on January 15, 2026"
        val timeMonthDayPattern = Regex("""at\s+(.+?)\s+(?:on\s+)?(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?""")
        timeMonthDayPattern.find(input)?.let { match ->
            val timeStr = match.groupValues[1]
            val monthName = match.groupValues[2]
            val day = match.groupValues[3].toIntOrNull() ?: return null
            val year = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull()
            val date = parseMonthDay(monthName, day, year) ?: return null
            val time = parseTimeString(timeStr) ?: return null
            return InterpretedDateTime.AbsoluteDateTime(LocalDateTime(date, time))
        }

        // Pattern: "1/15 at 3pm", "01/15 at 3pm"
        val numericDateTimePattern = Regex("""(\d{1,2})/(\d{1,2})\s+at\s+(.+)""")
        numericDateTimePattern.find(input)?.let { match ->
            val month = match.groupValues[1].toIntOrNull() ?: return null
            val day = match.groupValues[2].toIntOrNull() ?: return null
            val timeStr = match.groupValues[3]
            val date = parseNumericDate(month, day) ?: return null
            val time = parseTimeString(timeStr) ?: return null
            return InterpretedDateTime.AbsoluteDateTime(LocalDateTime(date, time))
        }

        return null
    }

    private fun parseAbsoluteTime(input: String): InterpretedDateTime.AbsoluteTime? {
        // Pattern: "at 3pm", "at 3:30pm", "at 15:00"
        val atTimePattern = Regex("""at\s+(.+)""")
        atTimePattern.find(input)?.let { match ->
            val timeStr = match.groupValues[1]
            val time = parseTimeString(timeStr) ?: return null
            return InterpretedDateTime.AbsoluteTime(time)
        }

        // Try parsing standalone time like "3pm", "3:30pm"
        parseTimeString(input)?.let { time ->
            return InterpretedDateTime.AbsoluteTime(time)
        }

        return null
    }

    private fun parseAbsoluteDate(input: String): InterpretedDateTime.AbsoluteDate? {
        // Pattern: "today", "tomorrow"
        val dayWordPattern = Regex("""^(today|tomorrow)$""")
        dayWordPattern.find(input)?.let { match ->
            val date = parseDayWord(match.groupValues[1]) ?: return null
            return InterpretedDateTime.AbsoluteDate(date)
        }

        // Pattern: "next Monday", "on Monday", "Monday"
        val dayOfWeekPattern = Regex("""(?:next|on)?\s*(monday|tuesday|wednesday|thursday|friday|saturday|sunday)$""")
        dayOfWeekPattern.find(input)?.let { match ->
            val date = parseNextDayOfWeek(match.groupValues[1]) ?: return null
            return InterpretedDateTime.AbsoluteDate(date)
        }

        // Pattern: "January 15", "on January 15", "January 15, 2026", "January 15 2026"
        val monthDayPattern = Regex("""(?:on\s+)?(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?$""")
        monthDayPattern.find(input)?.let { match ->
            val monthName = match.groupValues[1]
            val day = match.groupValues[2].toIntOrNull() ?: return null
            val year = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
            val date = parseMonthDay(monthName, day, year) ?: return null
            return InterpretedDateTime.AbsoluteDate(date)
        }

        // Pattern: "1/15", "01/15"
        val numericDatePattern = Regex("""^(\d{1,2})/(\d{1,2})$""")
        numericDatePattern.find(input)?.let { match ->
            val month = match.groupValues[1].toIntOrNull() ?: return null
            val day = match.groupValues[2].toIntOrNull() ?: return null
            val date = parseNumericDate(month, day) ?: return null
            return InterpretedDateTime.AbsoluteDate(date)
        }

        return null
    }

    private fun toRelative(amount: Long, unit: String): InterpretedDateTime.Relative? {
        val n = amount.toInt()
        return when (unit.lowercase().removeSuffix("s")) {
            "second" -> InterpretedDateTime.Relative(duration = amount.seconds)
            "minute" -> InterpretedDateTime.Relative(duration = amount.minutes)
            "hour" -> InterpretedDateTime.Relative(duration = amount.hours)
            "day" -> InterpretedDateTime.Relative(duration = amount.days)
            "week" -> InterpretedDateTime.Relative(duration = (amount * 7).days)
            "month" -> InterpretedDateTime.Relative(period = DatePeriod(months = n))
            "year" -> InterpretedDateTime.Relative(period = DatePeriod(years = n))
            else -> null
        }
    }

    private fun parseTimeString(timeStr: String): LocalTime? {
        val cleaned = timeStr.trim().lowercase()
            .replace(".", "")
            .replace(" ", "")

        // Pattern: "3pm", "3am", "12pm"
        val simplePattern = Regex("""^(\d{1,2})(am|pm)$""")
        simplePattern.find(cleaned)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val isPm = match.groupValues[2] == "pm"
            val adjustedHour = when {
                isPm && hour < 12 -> hour + 12
                !isPm && hour == 12 -> 0
                else -> hour
            }
            if (adjustedHour !in 0..23) return null
            return LocalTime(adjustedHour, 0)
        }

        // Pattern: "3:30pm", "3:30am", "12:45pm"
        val withMinutesPattern = Regex("""^(\d{1,2}):(\d{2})(am|pm)$""")
        withMinutesPattern.find(cleaned)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            val isPm = match.groupValues[3] == "pm"
            val adjustedHour = when {
                isPm && hour < 12 -> hour + 12
                !isPm && hour == 12 -> 0
                else -> hour
            }
            if (adjustedHour !in 0..23 || minute !in 0..59) return null
            return LocalTime(adjustedHour, minute)
        }

        // Pattern: "15:00", "08:30" (24-hour format)
        val militaryPattern = Regex("""^(\d{1,2}):(\d{2})$""")
        militaryPattern.find(cleaned)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return LocalTime(hour, minute)
        }

        return null
    }

    private fun parseDayWord(word: String): LocalDate? {
        return when (word.lowercase()) {
            "today" -> currentDateTime.date
            "tomorrow" -> currentDateTime.date + DatePeriod(days = 1)
            else -> null
        }
    }

    private fun parseNextDayOfWeek(dayName: String): LocalDate? {
        val targetDay = when (dayName.lowercase()) {
            "sunday" -> DayOfWeek.SUNDAY
            "monday" -> DayOfWeek.MONDAY
            "tuesday" -> DayOfWeek.TUESDAY
            "wednesday" -> DayOfWeek.WEDNESDAY
            "thursday" -> DayOfWeek.THURSDAY
            "friday" -> DayOfWeek.FRIDAY
            "saturday" -> DayOfWeek.SATURDAY
            else -> return null
        }

        val currentDay = currentDateTime.dayOfWeek
        val daysUntil = (targetDay.ordinal - currentDay.ordinal + 7) % 7
        val adjustedDays = if (daysUntil == 0) 7 else daysUntil // If same day, go to next week

        return currentDateTime.date + DatePeriod(days = adjustedDays)
    }

    private fun parseMonthDay(monthName: String, day: Int, explicitYear: Int? = null): LocalDate? {
        val month = when (monthName.lowercase()) {
            "january" -> Month.JANUARY
            "february" -> Month.FEBRUARY
            "march" -> Month.MARCH
            "april" -> Month.APRIL
            "may" -> Month.MAY
            "june" -> Month.JUNE
            "july" -> Month.JULY
            "august" -> Month.AUGUST
            "september" -> Month.SEPTEMBER
            "october" -> Month.OCTOBER
            "november" -> Month.NOVEMBER
            "december" -> Month.DECEMBER
            else -> return null
        }

        if (day !in 1..31) return null

        if (explicitYear != null) {
            return try {
                LocalDate(explicitYear, month, day)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        var year = currentDateTime.year
        val candidateDate = try {
            LocalDate(year, month, day)
        } catch (_: IllegalArgumentException) {
            return null
        }

        // If the date is in the past, assume next year
        if (candidateDate < currentDateTime.date) {
            year++
        }

        return try {
            LocalDate(year, month, day)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseNumericDate(month: Int, day: Int): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null

        var year = currentDateTime.year
        val candidateDate = try {
            LocalDate(year, month, day)
        } catch (_: IllegalArgumentException) {
            return null
        }

        // If the date is in the past, assume next year
        if (candidateDate < currentDateTime.date) {
            year++
        }

        return try {
            LocalDate(year, month, day)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

sealed class InterpretedDateTime {
    data class Relative(val duration: Duration = Duration.ZERO, val period: DatePeriod? = null) : InterpretedDateTime()
    data class AbsoluteDateTime(val dateTime: LocalDateTime) : InterpretedDateTime()
    data class AbsoluteTime(val time: LocalTime) : InterpretedDateTime()
    data class AbsoluteDate(val date: LocalDate) : InterpretedDateTime()
}

data class ParsedDateTimeResult(
    val dateTime: InterpretedDateTime,
    val matchedText: String,
    val range: IntRange
)