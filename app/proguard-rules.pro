# Add project specific ProGuard rules here.

-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-dontwarn fi.iki.elonen.**
