# The Claude API library (AI chat). Its own rules (in its jar) miss two things R8 needs:
# - its annotations, such as @ExcludeMissing, and the filters they name, which Jackson reads at run
#   time to leave unset fields out of requests ("JsonMissing cannot be serialized" without them);
# - its model classes as written: optimised, they send "null" for unset fields in some requests.
# Checked by shrinking tools/r8check with these rules and comparing its requests (see its README).
-keep @interface com.anthropic.** { *; }
-keep class com.anthropic.core.** { *; }
-keep,allowshrinking class com.anthropic.** { *; }
# Kept above but never called by the app: the library's structured-output helpers refer to
# reflection classes Android doesn't have.
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
