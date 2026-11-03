# libtorrent4j is SWIG-generated JNI: the native side calls back into these classes by
# name, so R8 must neither rename nor remove any of them.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**
