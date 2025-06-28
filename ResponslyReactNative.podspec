require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "ResponslyReactNative"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms     = { :ios => "15.0" }
  s.swift_version = "5.0"
  s.source        = { :git => "https://github.com/responsly/responsly-react-native-sdk.git", :tag => s.version.to_s }

  # The React Native bridge (ios/ResponslySdk.*, ios/ResponslySdkBridge.swift) and the sources of
  # the Responsly iOS SDK (ios/ResponslySDK, copied by scripts/sync-native.sh). Everything is
  # compiled into this one pod, so no other pod and no CocoaPods source is needed.
  s.source_files         = "ios/**/*.{h,m,mm,swift}"
  s.private_header_files = "ios/**/*.h"

  # The privacy manifest of the iOS SDK.
  s.resource_bundles = {
    "ResponslyReactNative" => ["ios/ResponslySDK/Resources/PrivacyInfo.xcprivacy"]
  }

  s.frameworks = "UIKit", "WebKit"
  s.pod_target_xcconfig = { "DEFINES_MODULE" => "YES" }

  install_modules_dependencies(s)
end
