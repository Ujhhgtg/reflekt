@file:Suppress("unused")

package dev.ujhhgtg.reflekt.reflected

import dev.ujhhgtg.reflekt.Reflekt
import java.lang.reflect.Method

open class BaseReflectedMethod internal constructor(
    val self: Method,
    internal val access: MethodAccess
) {
    val name: String get() = self.name
    val declaringClass: Class<*> get() = self.declaringClass
    val returnType: Class<*> get() = self.returnType
    val parameterTypes: Array<Class<*>> get() = self.parameterTypes
    val modifiers: Int get() = self.modifiers
    val annotations: Array<Annotation> get() = self.annotations
    val declaredAnnotations: Array<Annotation> get() = self.declaredAnnotations
    val isStatic: Boolean get() = access.isStatic

    /** Whether invocations go through a cached MethodHandle instead of core reflection. */
    val compiled: Boolean get() = access.compiled

    fun getAnnotation(annotationClass: Class<out Annotation>): Annotation? =
        self.getAnnotation(annotationClass)

    override fun toString(): String = self.toString()
    override fun hashCode(): Int = self.hashCode()
    override fun equals(other: Any?): Boolean =
        other is BaseReflectedMethod && self == other.self
}

/**
 * An unbound method. Invoked like the method itself: an instance method takes its receiver as the
 * first argument (`invoke(receiver, a, b)`), a static method does not (`invoke(a, b)`).
 */
open class ReflectedMethod<T> internal constructor(self: Method, access: MethodAccess) :
    BaseReflectedMethod(self, access) {

    constructor(self: Method, compiled: Boolean = Reflekt.defaults.compiled) :
            this(self, MethodAccess(self, compiled))

    fun invoke(): Any? = access.invoke0()
    fun invoke(arg0: Any?): Any? = access.invoke1(arg0)
    fun invoke(arg0: Any?, arg1: Any?): Any? = access.invoke2(arg0, arg1)
    fun invoke(arg0: Any?, arg1: Any?, arg2: Any?): Any? = access.invoke3(arg0, arg1, arg2)
    fun invoke(vararg args: Any?): Any? = access.invoke(args)

    /** Binds [instance] as the receiver. For a static method the instance is ignored. */
    fun of(instance: T & Any): InstanceReflectedMethod<T & Any> = InstanceReflectedMethod(instance, self, access)
}

/**
 * A method bound to an instance: arguments never include a receiver
 * (`invoke(a, b)` for both instance and static methods).
 */
class InstanceReflectedMethod<T : Any> internal constructor(
    val instance: T,
    self: Method,
    access: MethodAccess
) : BaseReflectedMethod(self, access) {

    fun invoke(): Any? =
        if (access.isStatic) access.invoke0() else access.invoke1(instance)

    fun invoke(arg0: Any?): Any? =
        if (access.isStatic) access.invoke1(arg0) else access.invoke2(instance, arg0)

    fun invoke(arg0: Any?, arg1: Any?): Any? =
        if (access.isStatic) access.invoke2(arg0, arg1) else access.invoke3(instance, arg0, arg1)

    fun invoke(arg0: Any?, arg1: Any?, arg2: Any?): Any? =
        if (access.isStatic) access.invoke3(arg0, arg1, arg2) else access.invoke4(instance, arg0, arg1, arg2)

    fun invoke(vararg args: Any?): Any? =
        if (access.isStatic) access.invoke(args) else access.invoke(arrayOf<Any?>(instance, *args))

    fun of(instance: T): InstanceReflectedMethod<T> = InstanceReflectedMethod(instance, self, access)

    fun ofNone(): ReflectedMethod<T> = ReflectedMethod(self, access)
}
