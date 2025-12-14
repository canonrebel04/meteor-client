#!/bin/bash
# Simplified deploy without clean (to avoid Loom cache issues)

MODS_DIR="/home/cachy/.local/share/PrismLauncher/instances/1.21.10/minecraft/mods"
METEOR_DIR="/home/cachy/meteor-client"
BARITONE_DIR="/home/cachy/baritone"

echo "=== Cleaning old jars from mods folder ==="
rm -f "$MODS_DIR"/meteor-client-*.jar
rm -f "$MODS_DIR"/baritone-*.jar

echo "=== Building Baritone ==="
cd "$BARITONE_DIR"
./gradlew build -x test

echo "=== Building Meteor Client ==="
cd "$METEOR_DIR"
./gradlew build -x test

echo "=== Deploying jars ==="
mkdir -p "$MODS_DIR"

# Copy Meteor Client jar
if [ -f "$METEOR_DIR"/build/libs/meteor-client-*.jar ]; then
    cp "$METEOR_DIR"/build/libs/meteor-client-*.jar "$MODS_DIR/"
    echo "✓ Deployed Meteor Client"
else
    echo "✗ Meteor Client jar not found!"
fi

# Copy Baritone jar
if [ -f "$BARITONE_DIR"/fabric/build/libs/baritone-*.jar ]; then
    cp "$BARITONE_DIR"/fabric/build/libs/baritone-*.jar "$MODS_DIR/"
    echo "✓ Deployed Baritone"
elif [ -f "$BARITONE_DIR"/build/libs/baritone-*.jar ]; then
    cp "$BARITONE_DIR"/build/libs/baritone-*.jar "$MODS_DIR/"
    echo "✓ Deployed Baritone"
else
    echo "✗ Baritone jar not found!"
fi

echo ""
echo "=== Deployment Complete ==="
ls -lh "$MODS_DIR"/*.jar 2>/dev/null || echo "No jars found!"
