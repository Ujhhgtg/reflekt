@file:Suppress("unused")

package dev.ujhhgtg.reflekt.reflected

import dev.ujhhgtg.reflekt.Reflekt
import java.lang.reflect.Constructor

open class ReflectedConstructor<T> internal constructor(
    val self: Constructor<T>,
    internal val access: ConstructorAccess<T>
) {
    constructor(self: Constructor<T>, compiled: Boolean = Reflekt.defaults.compiled) :
            this(self, ConstructorAccess(self, compiled))

    fun newInstance(vararg args: Any?): T = access.newInstance(args)

    val name: String get() = self.name
    val declaringClass: Class<*> get() = self.declaringClass
    val parameterTypes: Array<Class<*>> get() = self.parameterTypes
    val modifiers: Int get() = self.modifiers
    val annotations: Array<Annotation> get() = self.annotations
    val declaredAnnotations: Array<Annotation> get() = self.declaredAnnotations

    /** Whether construction goes through a cached MethodHandle instead of core reflection. */
    val compiled: Boolean get() = access.compiled

    fun getAnnotation(annotationClass: Class<out Annotation>): Annotation? =
        self.getAnnotation(annotationClass)

    override fun toString(): String = self.toString()
    override fun hashCode(): Int = self.hashCode()
    override fun equals(other: Any?): Boolean =
        other is ReflectedConstructor<*> && self == other.self
}

class InstanceReflectedConstructor<T : Any> internal constructor(
    private val instance: T,
    self: Constructor<T>,
    access: ConstructorAccess<T>
) : ReflectedConstructor<T>(self, access) {
    val instanceClass: Class<*> get() = instance::class.java
}
