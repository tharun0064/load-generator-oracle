#!/bin/bash

echo "Recompiling OracleLoadGenerator.java..."
javac -cp .:ojdbc8.jar OracleLoadGenerator.java

if [ $? -ne 0 ]; then
    echo "Error: Compilation failed"
    exit 1
fi

echo "Starting Oracle Load Generator in foreground..."
echo "Press Ctrl+C to stop"
echo ""

java -cp .:ojdbc8.jar OracleLoadGenerator
