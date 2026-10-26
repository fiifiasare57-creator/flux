#!/usr/bin/env bash
# Desktop-JVM end-to-end test of the Android protocol client against the real Rust receiver.
# Needs: JDK 17+, and `cargo build --release -p flux-cli` done from the repo root.
set -u
cd "$(dirname "$0")/../.."
BIN=target/release/flux; W=$(mktemp -d); mkdir -p $W/A $W/recv $W/classes $W/pcout; head -c 3500000 /dev/urandom > $W/pcout/from-pc.bin
keytool -genkeypair -alias k -keyalg EC -groupname secp256r1 -dname CN=phone-a -validity 3650 -storetype PKCS12 -keystore $W/a.p12 -storepass changeit -keypass changeit >/dev/null 2>&1
keytool -genkeypair -alias k -keyalg EC -groupname secp256r1 -dname CN=phone-b -validity 3650 -storetype PKCS12 -keystore $W/b.p12 -storepass changeit -keypass changeit >/dev/null 2>&1
javac -encoding UTF-8 -d $W/classes android/app/src/main/java/app/flux/protocol/*.java android/jvmtest/E2E.java || exit 2
$BIN --data-dir $W/A receive --port 47821 --dir $W/recv --name "Test-PC" --no-discovery --pair-ttl 300 --dev-outbox $W/pcout/from-pc.bin < /dev/null > $W/A.log 2>&1 &
RP=$!; sleep 2
LINK=$(grep -o 'flux://[^ ]*' $W/A.log | head -1 | sed 's/a=[0-9.]*%3A/a=127.0.0.1%3A/g')
java -cp $W/classes E2E "$LINK" $W/recv $W/a.p12 $W/b.p12 $W/pcout/from-pc.bin; RC=$?
kill $RP 2>/dev/null; echo "receiver history:"; $BIN --data-dir $W/A history | head -8; rm -rf $W; exit $RC
