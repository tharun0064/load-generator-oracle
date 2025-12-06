#!/bin/bash

echo "Recompiling OracleLoadGenerator.java..."
javac -cp .:ojdbc8.jar OracleLoadGenerator.java

if [ $? -ne 0 ]; then
    echo "Error: Compilation failed"
    exit 1
fi

echo "Starting Oracle Load Generator in background..."
echo "To stop: kill \$(cat load_generator.pid)"
echo ""

nohup java -cp .:ojdbc8.jar OracleLoadGenerator > /dev/null 2>&1 &
echo $! > load_generator.pid

echo "Load generator started with PID: $(cat load_generator.pid)"
