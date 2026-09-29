package dev.ujhhgtg.reflekt.reflected

import dev.ujhhgtg.reflekt.utils.makeAccessible
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/*
 * Execution backends shared by the Reflected* wrappers.
 *
 * Call convention (same as a MethodHandle of the member): an instance member takes its receiver
 * as the first argument, a static member takes none. Arguments are passed as a flat list whose
 * size is the member's "total arity".
 *
 * Two backends:
 * - reflection (default): Method.invoke / Field.get / Constructor.newInstance; the member is made
 *   accessible once, on first use. InvocationTargetException is unwrapped so both backends
 *   throw the target's own exception.
 * - compiled: a MethodHandle built lazily on first use and adapted once to an all-Object type,
 *   invoked with invokeExact. Up to 5 arguments are passed without an array.
 */

private val lookup: MethodHandles.Lookup = MethodHandles.lookup()

private fun arityError(kind: String, member: Any, isStatic: Boolean, expected: Int, actual: Int): Nothing {
    val hint = when {
        isStatic && actual == expected + 1 -> "; a static member takes no receiver, do not pass one"
        !isStatic && actual == expected - 1 -> "; an instance member takes its receiver as the first argument"
        else -> ""
    }
    throw IllegalArgumentException(
        "${if (isStatic) "static" else "instance"} $kind $member takes $expected argument(s), got $actual$hint"
    )
}

private inline fun <R> unwrapInvocationTarget(block: () -> R): R =
    try {
        block()
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }

/** Adapts [handle] (already of total arity [arity]) to (Object...)Object. */
private fun generic(handle: MethodHandle, arity: Int): MethodHandle =
    handle.asFixedArity().asType(MethodType.genericMethodType(arity))

/** A generic (Object...)Object handle plus its lazily built (Object[])Object spreader. */
internal class Invoker(private val direct: MethodHandle, private val arity: Int) {
    @Volatile
    private var spreader: MethodHandle? = null

    fun invoke(args: Array<out Any?>): Any? = when (args.size) {
        0 -> direct.invokeExact() as Any?
        1 -> direct.invokeExact(args[0]) as Any?
        2 -> direct.invokeExact(args[0], args[1]) as Any?
        3 -> direct.invokeExact(args[0], args[1], args[2]) as Any?
        4 -> direct.invokeExact(args[0], args[1], args[2], args[3]) as Any?
        5 -> direct.invokeExact(args[0], args[1], args[2], args[3], args[4]) as Any?
        else -> {
            val s = spreader ?: direct.asSpreader(Array<Any?>::class.java, arity).also { spreader = it }
            @Suppress("UNCHECKED_CAST")
            s.invokeExact(args as Array<Any?>) as Any?
        }
    }

    fun invoke0(): Any? = direct.invokeExact() as Any?
    fun invoke1(a: Any?): Any? = direct.invokeExact(a) as Any?
    fun invoke2(a: Any?, b: Any?): Any? = direct.invokeExact(a, b) as Any?
    fun invoke3(a: Any?, b: Any?, c: Any?): Any? = direct.invokeExact(a, b, c) as Any?
    fun invoke4(a: Any?, b: Any?, c: Any?, d: Any?): Any? = direct.invokeExact(a, b, c, d) as Any?
}

internal class MethodAccess(val method: Method, val compiled: Boolean) {
    val isStatic: Boolean = Modifier.isStatic(method.modifiers)
    val arity: Int = method.parameterCount + if (isStatic) 0 else 1

    @Volatile
    private var accessible = false

    @Volatile
    private var invoker: Invoker? = null

    private fun ensureAccessible() {
        if (!accessible) {
            method.makeAccessible()
            accessible = true
        }
    }

    private fun invoker(): Invoker = invoker ?: run {
        ensureAccessible()
        Invoker(generic(lookup.unreflect(method), arity), arity).also { invoker = it }
    }

    fun checkArity(actual: Int) {
        if (actual != arity) arityError("method", method, isStatic, arity, actual)
    }

    fun invoke(args: Array<out Any?>): Any? {
        checkArity(args.size)
        if (compiled) return invoker().invoke(args)
        ensureAccessible()
        return unwrapInvocationTarget {
            if (isStatic) method.invoke(null, *args)
            else method.invoke(args[0], *args.copyOfRange(1, args.size))
        }
    }

    // Fixed-arity fast paths: no intermediate argument arrays on either backend.
    fun invoke0(): Any? {
        checkArity(0)
        if (compiled) return invoker().invoke0()
        ensureAccessible()
        return unwrapInvocationTarget { method.invoke(null) }
    }

    fun invoke1(a: Any?): Any? {
        checkArity(1)
        if (compiled) return invoker().invoke1(a)
        ensureAccessible()
        return unwrapInvocationTarget { if (isStatic) method.invoke(null, a) else method.invoke(a) }
    }

    fun invoke2(a: Any?, b: Any?): Any? {
        checkArity(2)
        if (compiled) return invoker().invoke2(a, b)
        ensureAccessible()
        return unwrapInvocationTarget { if (isStatic) method.invoke(null, a, b) else method.invoke(a, b) }
    }

    fun invoke3(a: Any?, b: Any?, c: Any?): Any? {
        checkArity(3)
        if (compiled) return invoker().invoke3(a, b, c)
        ensureAccessible()
        return unwrapInvocationTarget { if (isStatic) method.invoke(null, a, b, c) else method.invoke(a, b, c) }
    }

    fun invoke4(a: Any?, b: Any?, c: Any?, d: Any?): Any? {
        checkArity(4)
        if (compiled) return invoker().invoke4(a, b, c, d)
        ensureAccessible()
        return unwrapInvocationTarget { if (isStatic) method.invoke(null, a, b, c, d) else method.invoke(a, b, c, d) }
    }

    /** True once a MethodHandle has been built (for tests). */
    internal val isHandleBuilt: Boolean get() = invoker != null
}

internal class ConstructorAccess<T>(val constructor: Constructor<T>, val compiled: Boolean) {
    val arity: Int = constructor.parameterCount

    @Volatile
    private var accessible = false

    @Volatile
    private var invoker: Invoker? = null

    private fun ensureAccessible() {
        if (!accessible) {
            constructor.makeAccessible()
            accessible = true
        }
    }

    private fun invoker(): Invoker = invoker ?: run {
        ensureAccessible()
        Invoker(generic(lookup.unreflectConstructor(constructor), arity), arity).also { invoker = it }
    }

    @Suppress("UNCHECKED_CAST")
    fun newInstance(args: Array<out Any?>): T {
        if (args.size != arity) {
            throw IllegalArgumentException("constructor $constructor takes $arity argument(s), got ${args.size}")
        }
        if (compiled) return invoker().invoke(args) as T
        ensureAccessible()
        return unwrapInvocationTarget { constructor.newInstance(*args) }
    }

    internal val isHandleBuilt: Boolean get() = invoker != null
}

internal class FieldAccess(val field: Field, val compiled: Boolean) {
    val isStatic: Boolean = Modifier.isStatic(field.modifiers)

    /** Arguments taken by get: the receiver, if any. */
    val getArity: Int = if (isStatic) 0 else 1

    @Volatile
    private var accessible = false

    // Getter and setter are built independently: a final field may be readable but not writable.
    @Volatile
    private var getter: MethodHandle? = null

    @Volatile
    private var setter: MethodHandle? = null

    private fun ensureAccessible() {
        if (!accessible) {
            field.makeAccessible()
            accessible = true
        }
    }

    private fun getter(): MethodHandle = getter ?: run {
        ensureAccessible()
        generic(lookup.unreflectGetter(field), getArity).also { getter = it }
    }

    // Adapted to return Object (null) instead of void, so the invokeExact call site type matches.
    private fun setter(): MethodHandle = setter ?: run {
        ensureAccessible()
        generic(lookup.unreflectSetter(field), getArity + 1).also { setter = it }
    }

    private fun checkGet(actual: Int) {
        if (actual != getArity) arityError("field get of", field, isStatic, getArity, actual)
    }

    private fun checkSet(actual: Int) {
        if (actual != getArity + 1) arityError("field set of", field, isStatic, getArity + 1, actual)
    }

    fun get0(): Any? {
        checkGet(0)
        if (compiled) return getter().invokeExact() as Any?
        ensureAccessible()
        return field.get(null)
    }

    fun get1(receiver: Any?): Any? {
        checkGet(1)
        if (compiled) return getter().invokeExact(receiver) as Any?
        ensureAccessible()
        return field.get(receiver)
    }

    fun set1(value: Any?) {
        checkSet(1)
        if (compiled) {
            @Suppress("UNUSED_VARIABLE")
            val ignored = setter().invokeExact(value) as Any?
            return
        }
        ensureAccessible()
        field.set(null, value)
    }

    fun set2(receiver: Any?, value: Any?) {
        checkSet(2)
        if (compiled) {
            @Suppress("UNUSED_VARIABLE")
            val ignored = setter().invokeExact(receiver, value) as Any?
            return
        }
        ensureAccessible()
        field.set(receiver, value)
    }

    internal val isHandleBuilt: Boolean get() = getter != null || setter != null
}
