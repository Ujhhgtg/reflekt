@file:Suppress("unused")

package dev.ujhhgtg.reflekt.reflected

import dev.ujhhgtg.reflekt.Reflekt
import java.lang.reflect.Field

open class BaseReflectedField internal constructor(
    val self: Field,
    internal val access: FieldAccess
) {
    val name: String get() = self.name
    val type: Class<*> get() = self.type
    val modifiers: Int get() = self.modifiers
    val declaringClass: Class<*> get() = self.declaringClass
    val annotations: Array<Annotation> get() = self.annotations
    val declaredAnnotations: Array<Annotation> get() = self.declaredAnnotations
    val isStatic: Boolean get() = access.isStatic

    /** Whether accesses go through cached MethodHandles instead of core reflection. */
    val compiled: Boolean get() = access.compiled

    fun getAnnotation(annotationClass: Class<out Annotation>): Annotation? =
        self.getAnnotation(annotationClass)

    override fun toString(): String = self.toString()
    override fun hashCode(): Int = self.hashCode()
    override fun equals(other: Any?): Boolean =
        other is BaseReflectedField && self == other.self
}

/**
 * An unbound field. An instance field takes its receiver (`get(receiver)`, `set(receiver, value)`),
 * a static field does not (`get()`, `set(value)`).
 */
open class ReflectedField<T> internal constructor(self: Field, access: FieldAccess) :
    BaseReflectedField(self, access) {

    constructor(self: Field, compiled: Boolean = Reflekt.defaults.compiled) :
            this(self, FieldAccess(self, compiled))

    fun get(): Any? = access.get0()
    fun get(receiver: Any?): Any? = access.get1(receiver)
    fun set(value: Any?) = access.set1(value)
    fun set(receiver: Any?, value: Any?) = access.set2(receiver, value)

    /** Binds [instance] as the receiver. For a static field the instance is ignored. */
    fun of(instance: T & Any): InstanceReflectedField<T & Any> = InstanceReflectedField(instance, self, access)
}

/** A field bound to an instance: `get()` / `set(value)` for both instance and static fields. */
class InstanceReflectedField<T : Any> internal constructor(
    val instance: T,
    self: Field,
    access: FieldAccess
) : BaseReflectedField(self, access) {

    fun get(): Any? = if (access.isStatic) access.get0() else access.get1(instance)

    fun set(value: Any?) = if (access.isStatic) access.set1(value) else access.set2(instance, value)

    fun of(instance: T): InstanceReflectedField<T> = InstanceReflectedField(instance, self, access)

    fun ofNone(): ReflectedField<T> = ReflectedField(self, access)
}
