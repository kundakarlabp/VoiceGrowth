# Google API client and Drive use reflection for JSON model fields.
-keepattributes Signature,*Annotation*
-keep class com.google.api.services.drive.model.** { *; }
-dontwarn org.apache.http.**
