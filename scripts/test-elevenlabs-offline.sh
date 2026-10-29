#!/usr/bin/env bash
# Offline subset only: does not replace scripts/gate.sh or compile Android/Kotlin.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
: "${TMPDIR:?Set TMPDIR to an approved scratch directory}"
out=$(mktemp -d "$TMPDIR/vox-elevenlabs-test.XXXXXX")
trap 'rm -rf "$out"' EXIT
javac --release 17 -Xlint:all -Werror -d "$out" \
  "$repo"/android/app/src/main/java/com/hermesvox/speech/*.java \
  "$repo"/tools/elevenlabs-test/*.java
java -cp "$out" com.hermesvox.speech.ElevenLabsProtocolTest
java -cp "$out" com.hermesvox.speech.SpeechChunksTest
java -cp "$out" com.hermesvox.speech.PcmStreamTest
java -cp "$out" com.hermesvox.speech.PcmDrainTest
java -cp "$out" com.hermesvox.speech.MicrophoneGateTest "$repo/android/app/src/main/java/com/hermesvox/VoiceController.kt"
java -cp "$out" com.hermesvox.speech.PlatformRearmTest "$repo/android/app/src/main/java/com/hermesvox/VoiceController.kt"
