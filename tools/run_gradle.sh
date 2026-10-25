#!/usr/bin/env bash
# ==============================================================================
# OmniDeck — Cross-Platform Gradle Runner (Linux / macOS)
# Automatically locates Java 17+ and executes the project Gradle Wrapper.
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Function to discover and set a valid Java 17+ installation
find_java17() {
    # 1. Existing JAVA_HOME check
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA_VER=$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
        if [ "$JAVA_VER" -ge 17 ] 2>/dev/null; then
            echo "$JAVA_HOME"
            return
        fi
    fi

    # 2. macOS common Homebrew and system JVM paths
    if [[ "$OSTYPE" == "darwin"* ]]; then
        if [ -x "/usr/libexec/java_home" ]; then
            JH=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
            if [ -n "$JH" ] && [ -d "$JH" ]; then
                echo "$JH"
                return
            fi
        fi
        for candidate in \
            "/opt/homebrew/opt/openjdk@17" \
            "/usr/local/opt/openjdk@17" \
            "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" \
            "/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"; do
            if [ -d "$candidate" ]; then
                echo "$candidate"
                return
            fi
        done
    fi

    # 3. Linux common JVM paths
    for candidate in \
        "/usr/lib/jvm/java-17-openjdk-amd64" \
        "/usr/lib/jvm/java-17-openjdk" \
        "/usr/lib/jvm/java-17-oracle" \
        "/usr/lib/jvm/default-java"; do
        if [ -d "$candidate" ]; then
            echo "$candidate"
            return
        fi
    fi
}

DETECTED_JAVA=$(find_java17)
if [ -n "$DETECTED_JAVA" ]; then
    export JAVA_HOME="$DETECTED_JAVA"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

# Ensure gradlew has execute permission
if [ -f "$DIR/gradlew" ] && [ ! -x "$DIR/gradlew" ]; then
    chmod +x "$DIR/gradlew" 2>/dev/null || true
fi

exec "$DIR/gradlew" "$@"
