#!/bin/sh
# Needs a JDK. org/json and StudioStore here are tiny stand-ins so BookModel can run without Android.
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -d out org/json/*.java com/hajala/app/*.java ../../app/src/main/java/com/hajala/app/BookModel.java
java -cp out com.hajala.app.BookModelTest
