# Copied from responsly-android-sdk by scripts/sync-native.sh. Do not edit here.
# Responsly SDK - rules applied to apps that use the SDK.

# The public API. The React Native, Flutter and Unity packages call it by name (JNI, reflection),
# so it must survive shrinking and keep its names. Classes in com.responsly.sdk.internal are free
# to be shrunk and renamed.
-keep public class com.responsly.sdk.* {
    public *;
}
-keep public interface com.responsly.sdk.* {
    *;
}
-keepclassmembers enum com.responsly.sdk.* {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# The page bridge needs nothing here: it is registered with
# androidx.webkit.WebViewCompat.addWebMessageListener and addDocumentStartJavaScript (never
# addJavascriptInterface), and androidx.webkit ships the rules for its own WebView boundary.
# The SDK's activity is kept through the manifest.
