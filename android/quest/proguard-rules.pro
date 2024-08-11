# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-keep class timber.** { *; }
-keep class org.smartregister.fhircore.** { *; }
-dontwarn org.smartregister.fhircore.**
-keep class * extends android.app.Activity
-keep class * extends androidx.appcompat.app.AppCompatActivity
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keep public class ** extends android.content.res.Resources {
    public static final int *;
}

-keep public class * extends android.util.ResourceEntry {
    public final java.lang.String name;
}
-keepdirectories assets/*
# Keep all classes and resources in the hapi-fhir-structures-r4 JAR
-keep class ca.uhn.fhir.** { *; }
-keep class org.hl7.fhir.r4.** { *; }

# Ensure the ResourceBundle classes are kept
-keep class **.hapi-messages { *; }
-keepnames class **.hapi-messages { *; }

# Keep all classes from the HAPI-FHIR package
-keep class org.hl7.fhir.instance.model.api.** { *; }
-keep class org.hl7.fhir.r4.model.** { *; }

# Prevent ProGuard from stripping out methods and fields used by reflection
-keepclassmembers class ca.uhn.fhir.** {
    *;
}
-keepclassmembers class org.hl7.fhir.r4.** {
    *;
}
-keepclassmembers class org.hl7.fhir.instance.model.api.** {
    *;
}

# Retrofit and OkHttp
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class com.google.android.fhir.** { *; }

# Keep Retrofit interfaces
-keep interface retrofit2.** { *; }
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations

# Keep Gson converter classes
-keep class com.google.gson.** { *; }
-keep class com.google.gson.annotations.** { *; }

# Keep method parameters
-keepclassmembernames class * {
    @retrofit2.http.* <methods>;
}

# Keep enums (if used)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}


# Preserve SQLCipher classes and methods
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-keep class net.sqlcipher.database.SQLiteDatabase {
    public *;
    protected *;
}

-keep class androidx.room.** { *; }
-keep @androidx.room.* class * { *; }

# Dagger/Hilt specific rules
-keep class dagger.hilt.internal.** { *; }
-keep class dagger.hilt.android.internal.** { *; }
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.Component class * { *; }

# General keep rules for reflection-based access
-keepclassmembers class * {
    @org.jetbrains.annotations.Nullable <fields>;
    @org.jetbrains.annotations.NotNull <fields>;
}