@file:JvmName("ReflektBenchmark")

package dev.ujhhgtg.reflekt.bench

import dev.ujhhgtg.reflekt.reflekt

/*
 * Rough micro-benchmark: reflection vs compiled backend. Not a JMH harness; use it to compare
 * the two backends on the same runtime (e.g. copy into an Android app and call runReflektBenchmark()).
 * On the JVM: ./gradlew compileTestKotlin, then run ReflektBenchmark.main with the test runtime classpath.
 *
 * Measure one backend per process (-Dorder=false, then -Dorder=true): both backends share call
 * sites, so whichever runs second in the same process is penalised by profile pollution.
 */

class BenchTarget {
    @JvmField
    var value: Int = 0

    fun zero(): Int = value
    fun two(a: Int, b: Int): Int = a + b
    fun seven(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int): Int = a + b + c + d + e + f + g
}

private inline fun measure(label: String, iterations: Int, block: () -> Unit): String {
    repeat(iterations / 10) { block() } // warm-up
    val start = System.nanoTime()
    repeat(iterations) { block() }
    val ns = (System.nanoTime() - start).toDouble() / iterations
    return "%-40s %8.1f ns/op".format(label, ns)
}

fun runReflektBenchmark(iterations: Int = 2_000_000): List<String> {
    val t = BenchTarget()
    val out = mutableListOf<String>()
    for (compiled in (System.getProperty("order") ?: "false,true").split(",").map(String::toBoolean)) {
        val tag = if (compiled) "compiled" else "reflection"
        val zero = t.reflekt().firstMethod { name = "zero"; compiled(compiled) }
        val two = t.reflekt().firstMethod { name = "two"; compiled(compiled) }
        val seven = t.reflekt().firstMethod { name = "seven"; compiled(compiled) }
        val field = t.reflekt().firstField { name = "value"; compiled(compiled) }
        val ctor = BenchTarget::class.reflekt().firstConstructor { compiled(compiled) }
        out += measure("[$tag] invoke() 0 args", iterations) { zero.invoke() }
        out += measure("[$tag] invoke(a, b) 2 args", iterations) { two.invoke(1, 2) }
        out += measure("[$tag] invoke(7 args) spread", iterations) { seven.invoke(1, 2, 3, 4, 5, 6, 7) }
        out += measure("[$tag] field get", iterations) { field.get() }
        out += measure("[$tag] field set", iterations) { field.set(1) }
        out += measure("[$tag] newInstance()", iterations) { ctor.newInstance() }
        // Cold cost: a fresh, uncached lookup + first call each time (lambda condition => not cached).
        out += measure("[$tag] cold lookup + first call", iterations / 100) {
            t.reflekt().firstMethod { name { it == "two" }; compiled(compiled) }.invoke(1, 2)
        }
    }
    return out
}

fun main() {
    runReflektBenchmark().forEach(::println)
}
