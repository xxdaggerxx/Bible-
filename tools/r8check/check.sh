#!/bin/sh
# Checks the Claude API library still works after R8 shrinking with app/proguard-rules.pro:
# builds a small program that makes the AI chat's request (to a local stand-in server) and
# reads a reply, shrinks it like the release build, and compares it with the unshrunk run.
# Run after a release build has fetched the library: sh tools/r8check/check.sh
set -e
cd "$(dirname "$0")"
C=$HOME/.gradle/caches/modules-2/files-2.1
jar() { find "$C/$1" -name "$2-*.jar" ! -name "*sources*" | sort | tail -1; }
DEPS="$(jar com.anthropic/anthropic-java-core anthropic-java-core) $(jar com.anthropic/anthropic-java-client-okhttp anthropic-java-client-okhttp)
 $(jar com.fasterxml.jackson.core/jackson-annotations jackson-annotations) $(jar com.fasterxml.jackson.core/jackson-core jackson-core)
 $(jar com.fasterxml.jackson.core/jackson-databind jackson-databind) $(jar com.fasterxml.jackson.datatype/jackson-datatype-jdk8 jackson-datatype-jdk8)
 $(jar com.fasterxml.jackson.datatype/jackson-datatype-jsr310 jackson-datatype-jsr310) $(jar com.fasterxml.jackson.module/jackson-module-kotlin jackson-module-kotlin)
 $(jar com.squareup.okhttp3/okhttp okhttp) $(jar com.squareup.okio/okio-jvm okio-jvm) $(jar org.jetbrains.kotlin/kotlin-reflect kotlin-reflect)
 $(jar org.jetbrains.kotlin/kotlin-stdlib/2.0.21 kotlin-stdlib) $(jar com.github.victools/jsonschema-module-jackson jsonschema-module-jackson)
 $(jar com.github.victools/jsonschema-generator jsonschema-generator) $(jar com.fasterxml/classmate classmate)"
CP=$(echo $DEPS | tr ' ' ':')
R8=$(find "$C/com.android.tools.build/builder" -name "builder-*.jar" | sort | tail -1)
T=$(mktemp -d)
unzip -p "$(jar com.anthropic/anthropic-java-core anthropic-java-core)" META-INF/proguard/anthropic-java-core.pro > "$T/sdk.pro"
javac -cp "$CP" -d "$T/classes" Main.java
(cd "$T/classes" && command jar cf ../main.jar ./*.class)
java -cp "$T/main.jar:$CP" Main > "$T/plain.txt"
java -cp "$R8" com.android.tools.r8.R8 --release --classfile --output "$T/out.jar" --lib "${JAVA_HOME:?set JAVA_HOME}" \
  --pg-conf base.pro --pg-conf "$T/sdk.pro" --pg-conf ../../app/proguard-rules.pro "$T/main.jar" $DEPS > /dev/null 2>&1
java -cp "$T/out.jar" Main > "$T/shrunk.txt"
if diff -q "$T/plain.txt" "$T/shrunk.txt" > /dev/null; then echo "OK: shrunk requests and replies match"; else diff "$T/plain.txt" "$T/shrunk.txt"; exit 1; fi
