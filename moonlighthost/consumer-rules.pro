# JNI entry points are looked up by name from libmoonlighthost.so.
-keepclasseswithmembernames class io.github.rasulnurmamedow.moonlighthost.** {
    native <methods>;
}
