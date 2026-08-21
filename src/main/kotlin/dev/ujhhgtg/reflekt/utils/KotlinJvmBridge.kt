package dev.ujhhgtg.reflekt.utils

import java.lang.invoke.MethodType
import java.lang.reflect.Method
import kotlin.jvm.internal.CallableReference
import kotlin.jvm.internal.ClassBasedDeclarationContainer
import kotlin.reflect.KFunction

// provides a O1-ish way primarily for lookup of stub methods
val KFunction<*>.fastJavaMethod: Method?
    get() {
        val ref = this as? CallableReference ?: return null
        val owner =
            ref.owner as? ClassBasedDeclarationContainer ?: return null

        val ownerClass = owner.jClass
        val signature = ref.signature

        val lParen = signature.indexOf('(')
        if (lParen <= 0)
            return null

        val jvmName = signature.substring(0, lParen)

        if (jvmName == "<init>")
            return null

        val methodType = MethodType.fromMethodDescriptorString(
            signature.substring(lParen),
            ownerClass.classLoader,
        )

        val parameterTypes = methodType.parameterArray()
        val returnType = methodType.returnType()

        val method = try {
            ownerClass.getDeclaredMethod(
                jvmName,
                *parameterTypes,
            )
        } catch (_: NoSuchMethodException) {
            return findExactMethod(
                ownerClass,
                jvmName,
                parameterTypes,
                returnType,
            )
        }

        if (method.returnType == returnType)
            return method

        return findExactMethod(
            ownerClass,
            jvmName,
            parameterTypes,
            returnType,
        )
    }

private fun findExactMethod(
    owner: Class<*>,
    name: String,
    parameterTypes: Array<Class<*>>,
    returnType: Class<*>,
): Method? =
    owner.declaredMethods.firstOrNull { method ->
        method.name == name &&
                method.returnType == returnType &&
                method.parameterTypes.contentEquals(parameterTypes)
    }
