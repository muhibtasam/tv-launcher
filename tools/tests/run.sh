#!/usr/bin/env bash
# Unit tests for the plain-Java logic (no Android needed): compiles and runs each test.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=out/test; rm -rf "$OUT"; mkdir -p "$OUT"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$OUT" app/src/io/vyro/tv/GamesLogic.java app/src/io/vyro/tv/IconShape.java app/src/io/vyro/tv/ContinueLogic.java app/src/io/vyro/tv/HomeNav.java app/src/io/vyro/tv/TitleWrap.java app/src/io/vyro/tv/MoviesLogic.java app/src/io/vyro/tv/TabsLogic.java app/src/io/vyro/tv/GamesV1Logic.java app/src/io/vyro/tv/AppsLogic.java app/src/io/vyro/tv/ControlCenterNav.java app/src/io/vyro/tv/Tr.java app/src/io/vyro/tv/NetRecoveryLogic.java app/src/io/vyro/tv/MemoryPolicy.java app/src/io/vyro/tv/ArrivalsLogic.java app/src/io/vyro/tv/MixLogic.java app/src/io/vyro/tv/AppContentLogic.java app/src/io/vyro/tv/UpdateLogic.java app/src/io/vyro/tv/AlertsLogic.java tools/tests/UpdateLogicTest.java tools/tests/AlertsLogicTest.java tools/tests/GamesLogicTest.java tools/tests/IconShapeTest.java tools/tests/ContinueLogicTest.java tools/tests/HomeNavTest.java tools/tests/TitleWrapTest.java tools/tests/MoviesLogicTest.java tools/tests/TabsLogicTest.java tools/tests/GamesV1LogicTest.java tools/tests/AppsLogicTest.java tools/tests/ControlCenterNavTest.java tools/tests/TrTest.java tools/tests/NetRecoveryLogicTest.java tools/tests/MemoryPolicyTest.java tools/tests/ArrivalsLogicTest.java tools/tests/MixLogicTest.java tools/tests/AppContentLogicTest.java
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
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" UpdateLogicTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" AlertsLogicTest
