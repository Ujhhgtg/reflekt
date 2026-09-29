@file:Suppress("unused")

package dev.ujhhgtg.reflekt

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Runs the call-convention and semantics tests against both execution backends. */
class BackendTest {

    // ========== Fixtures ==========

    open class Base {
        @JvmField
        var baseField: String = "base"
    }

    class Subject private constructor(@JvmField var value: Int) : Base() {
        constructor() : this(0)

        @JvmField
        val finalField: String = "final"

        @Volatile
        @JvmField
        var volatileField: Int = 1

        private var secret: String = "secret"

        var lastCall: String? = null

        fun plus(a: Int, b: Int): Int = value + a + b

        fun widen(x: Long): Long = x * 2

        fun unit(s: String) {
            lastCall = s
        }

        fun six(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int): Int = a + b + c + d + e + f

        fun boom(): Nothing = throw IllegalStateException("boom")

        private fun hidden(): String = "hidden"

        companion object {
            @JvmField
            var counter: Int = 0

            @JvmStatic
            fun twice(x: Int): Int = x * 2

            @JvmStatic
            fun staticSeven(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int): Int = a + b + c + d + e + f + g

            @JvmStatic
            fun staticBoom(): Nothing = throw UnsupportedOperationException("static boom")
        }
    }

    class ThrowingCtor {
        init {
            throw IllegalArgumentException("ctor boom")
        }
    }

    @AfterEach
    fun resetDefaults() {
        Reflekt.defaults.compiled = false
        Reflekt.defaults.superclass = false
        ReflectionCache.clear()
    }

    private fun subject(compiled: Boolean, name: String) =
        Subject::class.reflekt().firstMethod { this.name = name; compiled(compiled) }

    // ========== Methods: call convention ==========

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun instanceMethodUnboundTakesReceiver(compiled: Boolean) {
        val s = Subject()
        s.value = 10
        val m = subject(compiled, "plus")
        assertEquals(compiled, m.compiled)
        assertFalse(m.isStatic)
        assertEquals(13, m.invoke(s, 1, 2))
        assertEquals(13, m.invoke(*arrayOf<Any?>(s, 1, 2)))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun instanceMethodBoundTakesNoReceiver(compiled: Boolean) {
        val s = Subject()
        s.value = 10
        val m = s.reflekt().firstMethod { name = "plus"; compiled(compiled) }
        assertEquals(13, m.invoke(1, 2))
        assertEquals(13, m.invoke(*arrayOf<Any?>(1, 2)))
        assertEquals(13, s.reflekt().invokeMethod("plus", 1, 2))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun staticMethodTakesNoReceiver(compiled: Boolean) {
        val m = subject(compiled, "twice")
        assertTrue(m.isStatic)
        assertEquals(42, m.invoke(21))
        // Bound to an instance: the instance is ignored for a static method.
        assertEquals(42, m.of(Subject()).invoke(21))
        assertEquals(42, Subject::class.java.reflekt().invokeMethod("twice", 21))
        assertEquals(42, Integer::class.java.reflekt().firstMethod { name = "parseInt"; parameters(String::class.java); compiled(compiled) }.invoke("42"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun reflectInvokeMethodInstance(compiled: Boolean) {
        Reflekt.defaults.compiled = compiled
        val s = Subject()
        assertEquals(3, Subject::class.java.reflekt().invokeMethod("plus", s, 1, 2))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun manyArgumentsUseSpreadPath(compiled: Boolean) {
        val s = Subject()
        assertEquals(21, subject(compiled, "six").invoke(s, 1, 2, 3, 4, 5, 6))
        assertEquals(21, s.reflekt().firstMethod { name = "six"; compiled(compiled) }.invoke(1, 2, 3, 4, 5, 6))
        assertEquals(28, subject(compiled, "staticSeven").invoke(1, 2, 3, 4, 5, 6, 7))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun voidReturnsNull(compiled: Boolean) {
        val s = Subject()
        assertNull(subject(compiled, "unit").invoke(s, "x"))
        assertEquals("x", s.lastCall)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun primitiveWidening(compiled: Boolean) {
        // Method.invoke accepts an Integer for a long parameter; the handle backend must too.
        assertEquals(10L, subject(compiled, "widen").invoke(Subject(), 5))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun varargsParameterTakesArray(compiled: Boolean) {
        val format = String::class.java.reflekt().firstMethod {
            name = "format"; parameters(String::class.java, Array<Any>::class.java); compiled(compiled)
        }
        assertEquals("a-b", format.invoke("%s-%s", arrayOf<Any?>("a", "b")))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun privateMethod(compiled: Boolean) {
        assertEquals("hidden", subject(compiled, "hidden").invoke(Subject()))
    }

    // ========== Methods: errors ==========

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun exceptionsPropagateUnwrapped(compiled: Boolean) {
        val e = assertThrows<IllegalStateException> { subject(compiled, "boom").invoke(Subject()) }
        assertEquals("boom", e.message)
        assertThrows<UnsupportedOperationException> { subject(compiled, "staticBoom").invoke() }
        assertThrows<IllegalStateException> { Subject().reflekt().invokeMethod("boom") }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun arityErrors(compiled: Boolean) {
        val staticWithReceiver = assertThrows<IllegalArgumentException> { subject(compiled, "twice").invoke(null, 21) }
        assertTrue(staticWithReceiver.message!!.contains("static member takes no receiver"))
        val instanceWithoutReceiver = assertThrows<IllegalArgumentException> { subject(compiled, "plus").invoke(1, 2) }
        assertTrue(instanceWithoutReceiver.message!!.contains("receiver as the first argument"))
        assertThrows<IllegalArgumentException> { subject(compiled, "six").invoke(Subject(), 1) }
    }

    @Test
    fun wrongReceiverType() {
        // Reflection reports IllegalArgumentException, the handle backend ClassCastException.
        assertThrows<IllegalArgumentException> { subject(false, "plus").invoke("nope", 1, 2) }
        assertThrows<ClassCastException> { subject(true, "plus").invoke("nope", 1, 2) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun nullReceiverOnInstanceMethod(compiled: Boolean) {
        assertThrows<NullPointerException> { subject(compiled, "plus").invoke(null, 1, 2) }
    }

    // ========== Fields ==========

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun instanceField(compiled: Boolean) {
        val s = Subject()
        val f = Subject::class.reflekt().firstField { name = "value"; compiled(compiled) }
        f.set(s, 7)
        assertEquals(7, f.get(s))
        val bound = f.of(s)
        bound.set(8)
        assertEquals(8, bound.get())
        assertEquals(8, bound.ofNone().get(s))
        assertThrows<IllegalArgumentException> { f.get() }
        assertThrows<IllegalArgumentException> { f.set(1) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun staticField(compiled: Boolean) {
        val f = Subject::class.reflekt().firstField { name = "counter"; compiled(compiled) }
        f.set(5)
        assertEquals(5, f.get())
        assertEquals(5, f.of(Subject()).get())
        Subject::class.java.reflekt().setField("counter", 6)
        assertEquals(6, Subject::class.java.reflekt().getField("counter"))
        assertThrows<IllegalArgumentException> { f.get(Subject()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun privateFinalAndVolatileFields(compiled: Boolean) {
        val s = Subject()
        assertEquals("secret", Subject::class.reflekt().firstField { name = "secret"; compiled(compiled) }.get(s))
        assertEquals("final", Subject::class.reflekt().firstField { name = "finalField"; compiled(compiled) }.get(s))
        val v = s.reflekt().firstField { name = "volatileField"; compiled(compiled) }
        v.set(9)
        assertEquals(9, v.get())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun instanceFieldHelpers(compiled: Boolean) {
        Reflekt.defaults.compiled = compiled
        val s = Subject()
        s.reflekt().setField("value", 3)
        assertEquals(3, s.reflekt().getField("value"))
    }

    // ========== Constructors ==========

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun privateConstructor(compiled: Boolean) {
        val c = Subject::class.reflekt().firstConstructor { parameters(Int::class.java); compiled(compiled) }
        assertEquals(4, c.newInstance(4).value)
        assertThrows<IllegalArgumentException> { c.newInstance() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun constructorExceptionUnwrapped(compiled: Boolean) {
        val e = assertThrows<IllegalArgumentException> {
            ThrowingCtor::class.reflekt().firstConstructor { compiled(compiled) }.newInstance()
        }
        assertEquals("ctor boom", e.message)
    }

    // ========== Options, caching, laziness ==========

    @Test
    fun compiledDefaultsAndOverrides() {
        assertFalse(subject(false, "plus").compiled)
        assertFalse(Subject::class.reflekt().firstMethod { name = "plus" }.compiled)
        assertTrue(Subject::class.reflekt().firstMethod { name = "plus"; compiled() }.compiled)
        Reflekt.defaults.compiled = true
        assertTrue(Subject::class.reflekt().firstMethod { name = "plus" }.compiled)
        assertFalse(Subject::class.reflekt().firstMethod { name = "plus"; compiled(false) }.compiled)
        assertTrue(Subject::class.reflekt().firstField { name = "value" }.compiled)
        assertTrue(Subject::class.reflekt().firstConstructor().compiled)
    }

    @Test
    fun compiledIsPartOfCacheKey() {
        val plain = Subject::class.reflekt().firstMethod { name = "plus" }
        val compiled = Subject::class.reflekt().firstMethod { name = "plus"; compiled() }
        assertFalse(plain.compiled)
        assertTrue(compiled.compiled)
        assertSame(compiled, Subject::class.reflekt().firstMethod { name = "plus"; compiled() })
    }

    @Test
    fun handleIsBuiltLazilyAndShared() {
        val s = Subject()
        val m = Subject::class.reflekt().firstMethod { name = "plus"; compiled() }
        assertFalse(m.access.isHandleBuilt)
        m.self // metadata-only use builds nothing
        assertFalse(m.access.isHandleBuilt)
        val bound = s.reflekt().firstMethod { name = "plus"; compiled() }
        assertSame(m.access, bound.access)
        bound.invoke(1, 2)
        assertTrue(m.access.isHandleBuilt)

        val plain = Subject::class.reflekt().firstMethod { name = "plus" }
        plain.invoke(s, 1, 2)
        assertFalse(plain.access.isHandleBuilt)
    }

    @Test
    fun superclassDefault() {
        val s = Subject()
        // setField no longer defaults to superclass = true.
        assertThrows<NoSuchElementException> { s.reflekt().setField("baseField", "x") }
        s.reflekt().setField("baseField", "x", superclass = true)
        assertEquals("x", s.reflekt().getField("baseField", superclass = true))

        Reflekt.defaults.superclass = true
        s.reflekt().setField("baseField", "y")
        assertEquals("y", s.reflekt().getField("baseField"))
        assertThrows<NoSuchElementException> { s.reflekt().getField("baseField", superclass = false) }
    }
}
