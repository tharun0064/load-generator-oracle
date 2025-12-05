#!/bin/bash

echo "Oracle Load Generator - Java Setup and Run"
echo "==========================================="
echo ""

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed"
    echo ""
    echo "Install Java:"
    echo "  sudo yum install java-11-openjdk-devel    # RHEL/CentOS/Oracle Linux"
    echo "  sudo apt install openjdk-11-jdk           # Ubuntu/Debian"
    exit 1
fi

echo "Java version:"
java -version
echo ""

# Check if JDBC driver exists
if [ ! -f "ojdbc8.jar" ] && [ ! -f "ojdbc11.jar" ]; then
    echo "Error: Oracle JDBC driver not found (ojdbc8.jar or ojdbc11.jar)"
    echo ""
    echo "Download from: https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html"
    echo ""
    echo "Quick download (Oracle Database 21c JDBC):"
    echo "  wget https://download.oracle.com/otn-pub/otn_software/jdbc/217/ojdbc8.jar"
    echo ""
    echo "Or for Oracle 11g:"
    echo "  wget https://download.oracle.com/otn-pub/otn_software/jdbc/211/ojdbc11.jar"
    echo ""
    read -p "Continue anyway? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Determine which JDBC driver to use
JDBC_JAR=""
if [ -f "ojdbc11.jar" ]; then
    JDBC_JAR="ojdbc11.jar"
elif [ -f "ojdbc8.jar" ]; then
    JDBC_JAR="ojdbc8.jar"
fi

# Compile
echo "Compiling OracleLoadGenerator.java..."
if [ -n "$JDBC_JAR" ]; then
    javac -cp .:$JDBC_JAR OracleLoadGenerator.java
else
    javac OracleLoadGenerator.java
fi

if [ $? -ne 0 ]; then
    echo "Compilation failed"
    exit 1
fi

echo "Compilation successful!"
echo ""

# Run
echo "Starting Oracle Load Generator..."
echo "Press Ctrl+C to stop"
echo ""

if [ -n "$JDBC_JAR" ]; then
    java -cp .:$JDBC_JAR OracleLoadGenerator
else
    java OracleLoadGenerator
fi
