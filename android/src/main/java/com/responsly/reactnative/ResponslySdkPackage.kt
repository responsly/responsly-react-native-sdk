package com.responsly.reactnative

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class ResponslySdkPackage : BaseReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        if (name == ResponslySdkModule.NAME) ResponslySdkModule(reactContext) else null

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
        mapOf(
            ResponslySdkModule.NAME to ReactModuleInfo(
                ResponslySdkModule.NAME, // name
                ResponslySdkModule.NAME, // className
                false, // canOverrideExistingModule
                false, // needsEagerInit
                false, // isCxxModule
                BuildConfig.IS_NEW_ARCHITECTURE_ENABLED, // isTurboModule
            ),
        )
    }
}
