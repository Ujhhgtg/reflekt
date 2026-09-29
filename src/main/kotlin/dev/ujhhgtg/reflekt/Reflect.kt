@file:Suppress("unused")

package dev.ujhhgtg.reflekt

import dev.ujhhgtg.reflekt.reflected.InstanceReflectedConstructor
import dev.ujhhgtg.reflekt.reflected.InstanceReflectedField
import dev.ujhhgtg.reflekt.reflected.InstanceReflectedMethod
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import dev.ujhhgtg.reflekt.reflected.ReflectedField
import dev.ujhhgtg.reflekt.reflected.ReflectedMethod
import dev.ujhhgtg.reflekt.spec.ConstructorSpec
import dev.ujhhgtg.reflekt.spec.FieldSpec
import dev.ujhhgtg.reflekt.spec.MethodSpec
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

class Reflect<T>(private val clazz: Class<T>) {

    @Deprecated("You shouldn't call .reflekt() on a Reflect", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Reflect")

    // ==================== Methods ====================

    fun firstMethod(config: MethodSpec.() -> Unit): ReflectedMethod<T> {
        val spec = MethodSpec().apply(config)
        return resolveMethod(spec)
            ?: throw NoSuchElementException("No method matching conditions in ${clazz.name}")
    }

    fun firstMethodOrNull(config: MethodSpec.() -> Unit): ReflectedMethod<T>? {
        val spec = MethodSpec().apply(config)
        return resolveMethod(spec)
    }

    fun firstMethod(): ReflectedMethod<T> = firstMethod { }

    fun firstMethod(name: String): ReflectedMethod<T> = firstMethod { this.name = name }

    fun methods(config: MethodSpec.() -> Unit): List<ReflectedMethod<T>> {
        val spec = MethodSpec().apply(config)
        return resolveMethods(spec)
    }

    fun methods(): List<ReflectedMethod<T>> = methods { }

    fun lastMethod(config: MethodSpec.() -> Unit): ReflectedMethod<T> {
        val spec = MethodSpec().apply(config)
        return resolveLastMethod(spec)
            ?: throw NoSuchElementException("No method matching conditions in ${clazz.name}")
    }

    fun lastMethodOrNull(config: MethodSpec.() -> Unit): ReflectedMethod<T>? {
        val spec = MethodSpec().apply(config)
        return resolveLastMethod(spec)
    }

    fun lastMethod(): ReflectedMethod<T> = lastMethod { }

    /**
     * Invokes the first method named [name]. Like [ReflectedMethod.invoke]: pass the receiver as the
     * first argument for an instance method, and no receiver for a static method.
     * [superclass] defaults to [Reflekt.defaults].
     */
    fun invokeMethod(name: String, vararg args: Any?, superclass: Boolean? = null): Any? {
        return firstMethod {
            this.name = name
            if (superclass != null) superclass(superclass)
        }.invoke(*args)
    }

    // ==================== Fields ====================

    fun firstField(config: FieldSpec.() -> Unit): ReflectedField<T> {
        val spec = FieldSpec().apply(config)
        return resolveField(spec)
            ?: throw NoSuchElementException("No field matching conditions in ${clazz.name}")
    }

    fun firstFieldOrNull(config: FieldSpec.() -> Unit): ReflectedField<T>? {
        val spec = FieldSpec().apply(config)
        return resolveField(spec)
    }

    fun firstField(): ReflectedField<T> = firstField { }

    fun fields(config: FieldSpec.() -> Unit): List<ReflectedField<T>> {
        val spec = FieldSpec().apply(config)
        return resolveFields(spec)
    }

    fun fields(): List<ReflectedField<T>> = fields { }

    fun lastField(config: FieldSpec.() -> Unit): ReflectedField<T> {
        val spec = FieldSpec().apply(config)
        return resolveLastField(spec)
            ?: throw NoSuchElementException("No field matching conditions in ${clazz.name}")
    }

    fun lastFieldOrNull(config: FieldSpec.() -> Unit): ReflectedField<T>? {
        val spec = FieldSpec().apply(config)
        return resolveLastField(spec)
    }

    fun lastField(): ReflectedField<T> = lastField { }

    /** Reads the static field named [name]. [superclass] defaults to [Reflekt.defaults]. */
    fun getField(name: String, superclass: Boolean? = null): Any? {
        return firstField {
            this.name = name
            if (superclass != null) superclass(superclass)
        }.get()
    }

    /** Writes the static field named [name]. [superclass] defaults to [Reflekt.defaults]. */
    fun setField(name: String, value: Any?, superclass: Boolean? = null) {
        firstField {
            this.name = name
            if (superclass != null) superclass(superclass)
        }.set(value)
    }

    // ==================== Constructors ====================

    fun firstConstructor(config: ConstructorSpec.() -> Unit): ReflectedConstructor<T> {
        val spec = ConstructorSpec().apply(config)
        return resolveConstructor(spec)
            ?: throw NoSuchElementException("No constructor matching conditions in ${clazz.name}")
    }

    fun firstConstructorOrNull(config: ConstructorSpec.() -> Unit): ReflectedConstructor<T>? {
        val spec = ConstructorSpec().apply(config)
        return resolveConstructor(spec)
    }

    fun firstConstructor(): ReflectedConstructor<T> = firstConstructor { }

    fun constructors(config: ConstructorSpec.() -> Unit): List<ReflectedConstructor<T>> {
        val spec = ConstructorSpec().apply(config)
        return resolveConstructors(spec)
    }

    fun constructors(): List<ReflectedConstructor<T>> = constructors { }

    fun lastConstructor(config: ConstructorSpec.() -> Unit): ReflectedConstructor<T> {
        val spec = ConstructorSpec().apply(config)
        return resolveLastConstructor(spec)
            ?: throw NoSuchElementException("No constructor matching conditions in ${clazz.name}")
    }

    fun lastConstructorOrNull(config: ConstructorSpec.() -> Unit): ReflectedConstructor<T>? {
        val spec = ConstructorSpec().apply(config)
        return resolveLastConstructor(spec)
    }

    fun lastConstructor(): ReflectedConstructor<T> = lastConstructor { }

    // ==================== Method resolution ====================

    private fun resolveMethod(spec: MethodSpec): ReflectedMethod<T>? {
        if (spec.isCacheable) {
            val key = CacheKey(clazz, CacheMode.METHOD_FIRST, spec.staticCacheKeyParts())
            return ReflectionCache.getOrPut(key) { actualResolveMethod(spec) }
        }
        return actualResolveMethod(spec)
    }

    private fun resolveMethods(spec: MethodSpec): List<ReflectedMethod<T>> {
        if (spec.isCacheable) {
            val key = CacheKey(clazz, CacheMode.METHODS, spec.staticCacheKeyParts())
            return ReflectionCache.getOrPut(key) { actualResolveMethods(spec) }
        }
        return actualResolveMethods(spec)
    }

    private fun actualResolveMethod(spec: MethodSpec): ReflectedMethod<T>? =
        methodSource(spec.superclass).firstOrNull { spec.matches(it) }?.let { ReflectedMethod<T>(it, spec.compiled) }

    private fun actualResolveMethods(spec: MethodSpec): List<ReflectedMethod<T>> =
        methodSource(spec.superclass).filter { spec.matches(it) }.map { ReflectedMethod<T>(it, spec.compiled) }.toList()

    private fun methodSource(superclass: Boolean): Sequence<Method> =
        if (superclass) superclassSequence(clazz) { it.declaredMethods }
        else clazz.declaredMethods.asSequence()

    private fun resolveLastMethod(spec: MethodSpec): ReflectedMethod<T>? {
        if (spec.isCacheable) {
            val key = CacheKey(clazz, CacheMode.METHOD_LAST, spec.staticCacheKeyParts())
            return ReflectionCache.getOrPut(key) { actualResolveLastMethod(spec) }
        }
        return actualResolveLastMethod(spec)
    }

    private fun actualResolveLastMethod(spec: MethodSpec): ReflectedMethod<T>? =
        methodSource(spec.superclass).lastOrNull { spec.matches(it) }?.let { ReflectedMethod<T>(it, spec.compiled) }

    // ==================== Field resolution ====================

    private fun resolveField(spec: FieldSpec): ReflectedField<T>? {
        if (spec.isCacheable) {
            val key = CacheKey(clazz, CacheMode.FIELD_FIRST, spec.staticCacheKeyParts())
            return ReflectionCache.getOrPut(key) { actualResolveField(spec) }
        }
        return actualResolveField(spec)
    }

    private fun resolveFields(spec: FieldSpec): List<ReflectedField<T>> {
        if (spec.isCacheable) {
            val key = CacheKey(clazz, CacheMode.FIELDS, spec.staticCacheKeyParts())
            return ReflectionCache.getOrPut(key) { actualResolveFields(spec) }
        }
        return actualResolveFields(spec)
    }

    private fun actualResolveField(spec: FieldSpec): ReflectedField<T>? =
        fieldSource(spec.superclass).firstOrNull { spec.matches(it) }?.let { ReflectedField<T>(it, spec.compiled) }

    private fun actualResolveFields(spec: FieldSpec): List<ReflectedField<T>> =
        fieldSource(spec.superclass).filter { spec.matches(it) }.map { ReflectedField<T>(it, spec.compiled) }.toList()

    private fun fieldSource(superclass: Boolean): Sequence<Field> =
        if (superclass) superclassSequence(clazz) { it.declaredFields }
        else clazz.declaredFields.asSequence()

    private fun resolveLastField(spec: FieldSpec): ReflectedField<T>? {
        if (spec.isCacheable) {
            val key = CacheKey(clazz, CacheMode.FIELD_LAST, spec.staticCacheKeyParts())
            return ReflectionCache.getOrPut(key) { actualResolveLastField(spec) }
        }
        return actualResolveLastField(spec)
    }

    private fun actualResolveLastField(spec: FieldSpec): ReflectedField<T>? =
        fieldSource(spec.superclass).lastOrNull { spec.matches(it) }?.let { ReflectedField<T>(it, spec.compiled) }

    // ==================== Constructor resolution ====================

    private fun resolveConstructor(spec: ConstructorSpec): ReflectedConstructor<T>? {
        if (spec.isCacheable) {
            val key = CacheKey(clazz, CacheMode.CONSTRUCTOR_FIRST, spec.staticCacheKeyParts())
            return ReflectionCache.getOrPut(key) { actualResolveConstructor(spec) }
        }
        return actualResolveConstructor(spec)
    }

    private fun resolveConstructors(spec: ConstructorSpec): List<ReflectedConstructor<T>> {
        if (spec.isCacheable) {
            val key = CacheKey(clazz, CacheMode.CONSTRUCTORS, spec.staticCacheKeyParts())
            return ReflectionCache.getOrPut(key) { actualResolveConstructors(spec) }
        }
        return actualResolveConstructors(spec)
    }

    @Suppress("UNCHECKED_CAST")
    private fun actualResolveConstructor(spec: ConstructorSpec): ReflectedConstructor<T>? =
        constructorSource(spec.superclass).firstOrNull { spec.matches(it) }?.let {
            ReflectedConstructor(it as Constructor<T>, spec.compiled)
        }

    @Suppress("UNCHECKED_CAST")
    private fun actualResolveConstructors(spec: ConstructorSpec): List<ReflectedConstructor<T>> =
        constructorSource(spec.superclass).filter { spec.matches(it) }.map {
            ReflectedConstructor(it as Constructor<T>, spec.compiled)
        }.toList()

    private fun constructorSource(superclass: Boolean): Sequence<Constructor<*>> =
        if (superclass) superclassSequence(clazz) { it.declaredConstructors }
        else clazz.declaredConstructors.asSequence()

    private fun resolveLastConstructor(spec: ConstructorSpec): ReflectedConstructor<T>? {
        if (spec.isCacheable) {
            val key = CacheKey(clazz, CacheMode.CONSTRUCTOR_LAST, spec.staticCacheKeyParts())
            return ReflectionCache.getOrPut(key) { actualResolveLastConstructor(spec) }
        }
        return actualResolveLastConstructor(spec)
    }

    @Suppress("UNCHECKED_CAST")
    private fun actualResolveLastConstructor(spec: ConstructorSpec): ReflectedConstructor<T>? =
        constructorSource(spec.superclass).lastOrNull { spec.matches(it) }?.let {
            ReflectedConstructor(it as Constructor<T>, spec.compiled)
        }

    // ==================== Utility ====================

    private fun <M> superclassSequence(clazz: Class<*>, provider: (Class<*>) -> Array<M>): Sequence<M> = sequence {
        yieldAll(provider(clazz).asSequence())
        var current: Class<*>? = clazz.superclass
        while (current != null && current != Any::class.java) {
            yieldAll(provider(current).asSequence())
            current = current.superclass
        }
    }
}

class InstanceReflect<T : Any>(private val instance: T) {

    @Deprecated("You shouldn't call .reflekt() on an InstanceReflect", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on an InstanceReflect")

    @Suppress("UNCHECKED_CAST")
    private val reflect = Reflect(instance::class.java as Class<T>)

    // ==================== Methods ====================

    fun firstMethod(config: MethodSpec.() -> Unit): InstanceReflectedMethod<T> {
        val rm = reflect.firstMethod(config)
        return InstanceReflectedMethod(instance, rm.self, rm.access)
    }

    fun firstMethodOrNull(config: MethodSpec.() -> Unit): InstanceReflectedMethod<T>? {
        val rm = reflect.firstMethodOrNull(config) ?: return null
        return InstanceReflectedMethod(instance, rm.self, rm.access)
    }

    fun firstMethod(): InstanceReflectedMethod<T> = firstMethod { }

    fun methods(config: MethodSpec.() -> Unit): List<InstanceReflectedMethod<T>> =
        reflect.methods(config).map { InstanceReflectedMethod(instance, it.self, it.access) }

    fun methods(): List<InstanceReflectedMethod<T>> = methods { }

    fun lastMethod(config: MethodSpec.() -> Unit): InstanceReflectedMethod<T> {
        val rm = reflect.lastMethod(config)
        return InstanceReflectedMethod(instance, rm.self, rm.access)
    }

    fun lastMethodOrNull(config: MethodSpec.() -> Unit): InstanceReflectedMethod<T>? {
        val rm = reflect.lastMethodOrNull(config) ?: return null
        return InstanceReflectedMethod(instance, rm.self, rm.access)
    }

    fun lastMethod(): InstanceReflectedMethod<T> = lastMethod { }

    /** Invokes the first method named [name] on the instance. [superclass] defaults to [Reflekt.defaults]. */
    fun invokeMethod(name: String, vararg args: Any?, superclass: Boolean? = null): Any? {
        return firstMethod {
            this.name = name
            if (superclass != null) superclass(superclass)
        }.invoke(*args)
    }

    // ==================== Fields ====================

    fun firstField(config: FieldSpec.() -> Unit): InstanceReflectedField<T> {
        val rf = reflect.firstField(config)
        return InstanceReflectedField(instance, rf.self, rf.access)
    }

    fun firstFieldOrNull(config: FieldSpec.() -> Unit): InstanceReflectedField<T>? {
        val rf = reflect.firstFieldOrNull(config) ?: return null
        return InstanceReflectedField(instance, rf.self, rf.access)
    }

    fun firstField(): InstanceReflectedField<T> = firstField { }

    fun fields(config: FieldSpec.() -> Unit): List<InstanceReflectedField<T>> =
        reflect.fields(config).map { InstanceReflectedField(instance, it.self, it.access) }

    fun fields(): List<InstanceReflectedField<T>> = fields { }

    fun lastField(config: FieldSpec.() -> Unit): InstanceReflectedField<T> {
        val rf = reflect.lastField(config)
        return InstanceReflectedField(instance, rf.self, rf.access)
    }

    fun lastFieldOrNull(config: FieldSpec.() -> Unit): InstanceReflectedField<T>? {
        val rf = reflect.lastFieldOrNull(config) ?: return null
        return InstanceReflectedField(instance, rf.self, rf.access)
    }

    fun lastField(): InstanceReflectedField<T> = lastField { }

    /** Reads the field named [name] on the instance. [superclass] defaults to [Reflekt.defaults]. */
    fun getField(name: String, superclass: Boolean? = null): Any? {
        return firstField {
            this.name = name
            if (superclass != null) superclass(superclass)
        }.get()
    }

    /** Writes the field named [name] on the instance. [superclass] defaults to [Reflekt.defaults]. */
    fun setField(name: String, value: Any?, superclass: Boolean? = null) {
        firstField {
            this.name = name
            if (superclass != null) superclass(superclass)
        }.set(value)
    }

    // ==================== Constructors ====================

    fun firstConstructor(config: ConstructorSpec.() -> Unit): InstanceReflectedConstructor<T> {
        val rc = reflect.firstConstructor(config)
        return InstanceReflectedConstructor(instance, rc.self, rc.access)
    }

    fun firstConstructorOrNull(config: ConstructorSpec.() -> Unit): InstanceReflectedConstructor<T>? {
        val rc = reflect.firstConstructorOrNull(config) ?: return null
        return InstanceReflectedConstructor(instance, rc.self, rc.access)
    }

    fun firstConstructor(): InstanceReflectedConstructor<T> = firstConstructor { }

    fun constructors(config: ConstructorSpec.() -> Unit): List<InstanceReflectedConstructor<T>> =
        reflect.constructors(config).map { InstanceReflectedConstructor(instance, it.self, it.access) }

    fun constructors(): List<InstanceReflectedConstructor<T>> = constructors { }

    fun lastConstructor(config: ConstructorSpec.() -> Unit): InstanceReflectedConstructor<T> {
        val rc = reflect.lastConstructor(config)
        return InstanceReflectedConstructor(instance, rc.self, rc.access)
    }

    fun lastConstructorOrNull(config: ConstructorSpec.() -> Unit): InstanceReflectedConstructor<T>? {
        val rc = reflect.lastConstructorOrNull(config) ?: return null
        return InstanceReflectedConstructor(instance, rc.self, rc.access)
    }

    fun lastConstructor(): InstanceReflectedConstructor<T> = lastConstructor { }
}
