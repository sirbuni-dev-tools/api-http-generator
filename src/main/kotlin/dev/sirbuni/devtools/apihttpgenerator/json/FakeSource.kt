package dev.sirbuni.devtools.apihttpgenerator.json

import net.datafaker.Faker
import java.util.Random

/** Random values for the fixed-value mode, backed by Datafaker. A seed makes the output repeatable in tests. */
class FakeSource(seed: Long? = null) {
    private val random = if (seed == null) Random() else Random(seed)
    private val faker = Faker(random)

    /** Calls `faker.<provider>().<method>()`, the method names the HTTP Client's `$random` uses. */
    fun faker(provider: String, method: String): String {
        val providerObject = faker.javaClass.getMethod(provider).invoke(faker)
        return providerObject.javaClass.getMethod(method).invoke(providerObject).toString()
    }

    fun long(min: Long, max: Long): Long = if (max <= min) min else min + (random.nextDouble() * (max - min + 1)).toLong().coerceAtMost(max - min)

    fun double(min: Double, max: Double): Double = if (max <= min) min else min + random.nextDouble() * (max - min)

    fun letters(length: Int, withDigits: Boolean): String {
        val pool = if (withDigits) "abcdefghijklmnopqrstuvwxyz0123456789" else "abcdefghijklmnopqrstuvwxyz"
        return (1..length).map { pool[random.nextInt(pool.length)] }.joinToString("")
    }

    fun regexify(regex: String): String = faker.regexify(regex)

    /** Days from today: up to two years back, up to two years ahead, or either. */
    fun offsetDays(timing: Timing): Long = when (timing) {
        Timing.PAST -> -long(1, 730)
        Timing.FUTURE -> long(1, 730)
        Timing.ANY -> long(-365, 365)
    }
}
