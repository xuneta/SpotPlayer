# SpotPlayer: keep the app's own classes (same policy as the manual R8 build's keep.pro);
# Media3/AAR consumer rules handle the libraries.
-keep class com.arman.spotplayer.** { *; }
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
