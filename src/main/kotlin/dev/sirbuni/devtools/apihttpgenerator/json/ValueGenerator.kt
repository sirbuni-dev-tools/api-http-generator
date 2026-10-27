package dev.sirbuni.devtools.apihttpgenerator.json

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Produces one leaf value in two forms: an HTTP Client dynamic variable, when one exists,
 * and a fixed fake value picked now.
 */
sealed interface ValueGenerator {
    /** Whether the value is a JSON string, so the renderer knows to quote it. */
    val isString: Boolean

    /** The expression inside `{{ }}`, or null when the HTTP Client has no generator for it. */
    val placeholder: String?

    fun concrete(source: FakeSource): String
}

/** A Faker provider method, the same name in the HTTP Client's `$random` and in Datafaker. */
data class FakerValue(val provider: String, val method: String, override val isString: Boolean = true) : ValueGenerator {
    override val placeholder get() = "\$random.$provider.$method"
    override fun concrete(source: FakeSource) = source.faker(provider, method)
}

/** Whole numbers from [min] to [max], both inclusive. */
data class IntegerRange(val min: Long, val max: Long) : ValueGenerator {
    override val isString = false

    // $random.integer works on int and excludes its upper bound; a single possible value is written as is.
    override val placeholder: String?
        get() = if (min != max && min >= Int.MIN_VALUE && max < Int.MAX_VALUE) "\$random.integer($min, ${max + 1})" else null

    override fun concrete(source: FakeSource) = source.long(min, max).toString()
}

data class DecimalRange(val min: Double, val max: Double) : ValueGenerator {
    override val isString = false
    override val placeholder get() = "\$random.float(${plain(min)}, ${plain(max)})"
    override fun concrete(source: FakeSource) = "%.2f".format(java.util.Locale.ROOT, source.double(min, max))

    private fun plain(d: Double) = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
}

data object UuidValue : ValueGenerator {
    override val isString = true
    override val placeholder = "\$uuid"
    override fun concrete(source: FakeSource) = java.util.UUID.randomUUID().toString()
}

/** A point in time with an offset, for `Instant`, `OffsetDateTime`, `ZonedDateTime` and `Date`. */
data object IsoTimestamp : ValueGenerator {
    override val isString = true
    override val placeholder = "\$isoTimestamp"
    override fun concrete(source: FakeSource): String =
        Instant.now().minus(Duration.ofMinutes(source.long(0, 60L * 24 * 30))).truncatedTo(ChronoUnit.SECONDS).toString()
}

data class Letters(val length: Int, val withDigits: Boolean = false) : ValueGenerator {
    override val isString = true
    override val placeholder get() = if (withDigits) "\$random.alphanumeric($length)" else "\$random.alphabetic($length)"
    override fun concrete(source: FakeSource) = source.letters(length, withDigits)
}

enum class Timing { PAST, FUTURE, ANY }

data class DateValue(val timing: Timing = Timing.ANY) : ValueGenerator {
    override val isString = true
    override val placeholder: String? = null
    override fun concrete(source: FakeSource): String = LocalDate.now().plusDays(source.offsetDays(timing)).toString()
}

data class DateTimeValue(val timing: Timing = Timing.ANY) : ValueGenerator {
    override val isString = true
    override val placeholder: String? = null
    override fun concrete(source: FakeSource): String =
        LocalDate.now().plusDays(source.offsetDays(timing)).atTime(source.long(7, 18).toInt(), source.long(0, 59).toInt()).toString() + ":00"
}

data object TimeValue : ValueGenerator {
    override val isString = true
    override val placeholder: String? = null
    override fun concrete(source: FakeSource): String =
        LocalTime.of(source.long(7, 18).toInt(), source.long(0, 59).toInt()).toString() + ":00"
}

data object BooleanValue : ValueGenerator {
    override val isString = false
    override val placeholder: String? = null
    override fun concrete(source: FakeSource) = (source.long(0, 1) == 1L).toString()
}

/** One of a fixed set, such as enum constants. */
data class OneOf(val values: List<String>, override val isString: Boolean = true) : ValueGenerator {
    override val placeholder: String? = null
    override fun concrete(source: FakeSource) = values[source.long(0, values.size - 1L).toInt()]
}

/** A string matching a `@Pattern` regular expression. */
data class PatternValue(val regex: String) : ValueGenerator {
    override val isString = true
    override val placeholder: String? = null
    override fun concrete(source: FakeSource) = source.regexify(regex)
}

/** An explicit `null`, for values no fake can get right, such as the property to sort by. */
data object NullValue : ValueGenerator {
    override val isString = false
    override val placeholder: String? = null
    override fun concrete(source: FakeSource) = "null"
}

/** `byte[]`, which Jackson writes as Base64. */
data object Base64Value : ValueGenerator {
    override val isString = true
    override val placeholder: String? = null
    override fun concrete(source: FakeSource) = java.util.Base64.getEncoder().encodeToString(source.letters(12, true).toByteArray())
}
