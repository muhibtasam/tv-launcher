#!/usr/bin/env bash
# Unit tests for the plain-Java logic (no Android needed): compiles and runs each test.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=out/test; rm -rf "$OUT"; mkdir -p "$OUT"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$OUT" app/src/io/freemytv/GamesLogic.java app/src/io/freemytv/IconShape.java app/src/io/freemytv/ContinueLogic.java app/src/io/freemytv/HomeNav.java app/src/io/freemytv/TitleWrap.java app/src/io/freemytv/MoviesLogic.java app/src/io/freemytv/TabsLogic.java app/src/io/freemytv/GamesV1Logic.java app/src/io/freemytv/AppsLogic.java app/src/io/freemytv/ControlCenterNav.java app/src/io/freemytv/Tr.java app/src/io/freemytv/NetRecoveryLogic.java app/src/io/freemytv/MemoryPolicy.java app/src/io/freemytv/ArrivalsLogic.java app/src/io/freemytv/MixLogic.java app/src/io/freemytv/AppContentLogic.java tools/tests/GamesLogicTest.java tools/tests/IconShapeTest.java tools/tests/ContinueLogicTest.java tools/tests/HomeNavTest.java tools/tests/TitleWrapTest.java tools/tests/MoviesLogicTest.java tools/tests/TabsLogicTest.java tools/tests/GamesV1LogicTest.java tools/tests/AppsLogicTest.java tools/tests/ControlCenterNavTest.java tools/tests/TrTest.java tools/tests/NetRecoveryLogicTest.java tools/tests/MemoryPolicyTest.java tools/tests/ArrivalsLogicTest.java tools/tests/MixLogicTest.java tools/tests/AppContentLogicTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" GamesLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" IconShapeTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" ContinueLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" HomeNavTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" TitleWrapTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" MoviesLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" TabsLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" GamesV1LogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" AppsLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" ControlCenterNavTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" TrTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" NetRecoveryLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" MemoryPolicyTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" ArrivalsLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" MixLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" AppContentLogicTest
