#!/bin/bash

echo "=========================================="
echo "Oracle Load Generator - Setup Guide"
echo "Oracle Linux Installation"
echo "=========================================="
echo ""

# Step 1: Install Java
echo "Step 1: Installing Java..."
sudo yum install -y java-11-openjdk-devel

if [ $? -ne 0 ]; then
    echo "Error: Failed to install Java"
    exit 1
fi

echo ""
echo "Java installed successfully:"
java -version
echo ""

# Step 2: Download Oracle JDBC Driver
echo "Step 2: Downloading Oracle JDBC Driver (ojdbc8.jar)..."

if [ ! -f "ojdbc8.jar" ]; then
    wget https://download.oracle.com/otn-pub/otn_software/jdbc/217/ojdbc8.jar
    
    if [ $? -ne 0 ]; then
        echo ""
        echo "Warning: Automatic download failed."
        echo "Please manually download ojdbc8.jar from:"
        echo "https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html"
        echo ""
        echo "Or try this alternative URL:"
        echo "wget https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc8/21.7.0.0/ojdbc8-21.7.0.0.jar -O ojdbc8.jar"
        exit 1
    fi
else
    echo "ojdbc8.jar already exists, skipping download"
fi

echo ""
echo "JDBC driver ready!"
echo ""

# Step 3: Compile
echo "Step 3: Compiling OracleLoadGenerator.java..."

if [ ! -f "OracleLoadGenerator.java" ]; then
    echo "Error: OracleLoadGenerator.java not found in current directory"
    exit 1
fi

javac -cp .:ojdbc8.jar OracleLoadGenerator.java

if [ $? -ne 0 ]; then
    echo "Error: Compilation failed"
    exit 1
fi

echo "Compilation successful!"
echo ""

# Step 4: Run
echo "=========================================="
echo "Setup Complete!"
echo "=========================================="
echo ""
echo "To run the load generator:"
echo "  java -cp .:ojdbc8.jar OracleLoadGenerator"
echo ""
echo "Or use the helper script:"
echo "  ./start_load.sh"
echo ""

# Create start script
cat > start_load.sh << 'EOF'
#!/bin/bash

echo "Starting Oracle Load Generator..."
echo "Press Ctrl+C to stop"
echo ""

java -cp .:ojdbc8.jar OracleLoadGenerator
EOF

chmod +x start_load.sh

echo "Created start_load.sh helper script"
echo ""
echo "Ready to generate load!"
