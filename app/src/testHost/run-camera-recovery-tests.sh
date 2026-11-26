#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
test_classes="$(mktemp -d "${TMPDIR:-/tmp}/keenon-camera-tests.XXXXXX")"
trap 'rm -rf "$test_classes"' EXIT

javac -encoding UTF-8 -d "$test_classes" \
    "$repository_root/app/src/main/java/com/serenegiant/usbcameratest7/CameraRecoveryController.java" \
    "$repository_root/app/src/testHost/com/serenegiant/usbcameratest7/CameraRecoveryControllerTest.java"
java -cp "$test_classes" com.serenegiant.usbcameratest7.CameraRecoveryControllerTest
