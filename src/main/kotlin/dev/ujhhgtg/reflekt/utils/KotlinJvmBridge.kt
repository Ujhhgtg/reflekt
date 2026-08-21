package dev.ujhhgtg.reflekt.utils

import java.lang.reflect.Method
import kotlin.jvm.internal.KotlinGenericDeclaration
import kotlin.reflect.KFunction

val KFunction<*>.fastJavaMethod: Method?
    get() = (this as? KotlinGenericDeclaration)
        ?.findJavaDeclaration() as? Method
