#!/bin/bash

if [ ! -f load_generator.pid ]; then
    echo "Error: load_generator.pid not found"
    echo "Load generator may not be running"
    exit 1
fi

PID=$(cat load_generator.pid)

if ps -p $PID > /dev/null 2>&1; then
    echo "Stopping load generator (PID: $PID)..."
    kill $PID
    sleep 2
    
    if ps -p $PID > /dev/null 2>&1; then
        echo "Process still running, forcing stop..."
        kill -9 $PID
    fi
    
    rm -f load_generator.pid
    echo "Load generator stopped"
else
    echo "Process $PID not found (already stopped)"
    rm -f load_generator.pid
fi
