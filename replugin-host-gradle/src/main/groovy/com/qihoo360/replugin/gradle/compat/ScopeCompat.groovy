package com.qihoo360.replugin.gradle.compat

/**
 * @author hyongbai
 */
class ScopeCompat {
    static def getAdbExecutable(def scope) {
        final MetaClass scopeClz = scope.metaClass
        if (scopeClz.hasProperty(scope, "androidBuilder")) {
            return scope.androidBuilder.sdkInfo.adb
        }
        if (scopeClz.hasProperty(scope, "sdkComponents")) {
            return scope.sdkComponents.adbExecutableProvider.get()
        }
    }
}