# Oracle Load Generator - Oracle Linux Quick Start

## Method 1: Automated Setup (Recommended)

```bash
# 1. Copy files to Oracle Linux instance
scp OracleLoadGenerator.java setup_oracle_linux.sh user@your-oracle-linux-ip:~/

# 2. SSH to Oracle Linux
ssh user@your-oracle-linux-ip

# 3. Run automated setup
chmod +x setup_oracle_linux.sh
./setup_oracle_linux.sh

# 4. Start the load generator
./start_load.sh
```

## Method 2: Manual Setup

### Step 1: Install Java
```bash
sudo yum install -y java-11-openjdk-devel
java -version
```

### Step 2: Download Oracle JDBC Driver
```bash
# Option A: Direct download (may require Oracle account)
wget https://download.oracle.com/otn-pub/otn_software/jdbc/217/ojdbc8.jar

# Option B: Maven Central (no account needed)
wget https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc8/21.7.0.0/ojdbc8-21.7.0.0.jar -O ojdbc8.jar

# Option C: If on Oracle Linux with Oracle DB installed, JDBC driver may already be available
find /usr/lib/oracle -name "ojdbc*.jar" 2>/dev/null
# If found, copy to current directory:
# cp /usr/lib/oracle/21/client64/lib/ojdbc8.jar .
```

### Step 3: Compile
```bash
javac -cp .:ojdbc8.jar OracleLoadGenerator.java
```

### Step 4: Run
```bash
java -cp .:ojdbc8.jar OracleLoadGenerator
```

## What You'll See

```
Oracle Database Load Generator
===============================

Setting up test tables and data...
Creating tables...
Inserting test data (50,000 orders)...
Test data setup complete!
Starting load generators...
All load generators started successfully!
Starting scheduled metrics generator (runs every 10 seconds)...

Load generation running. Press Ctrl+C to stop...

Executed CPU-intensive query
Executed I/O-intensive operation
Starting blocker session (will hold locks)
Blocker session acquired locks on rows 1-50
Blocked session 0 attempting to acquire locks...
Blocked session 1 attempting to acquire locks...

=== Scheduled Metrics Burst ===
  [Scheduled] Generated tablespace pressure
  [Scheduled] Generated temp space usage
  [Scheduled] Generated library cache contention (50 unique SQLs)
  [Scheduled] Generated checkpoint activity
=== Metrics burst triggered ===
```

## Running as Background Service (Optional)

### Create systemd service:
```bash
sudo cat > /etc/systemd/system/oracle-load-generator.service << EOF
[Unit]
Description=Oracle Database Load Generator
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$HOME
ExecStart=/usr/bin/java -cp $HOME:$HOME/ojdbc8.jar OracleLoadGenerator
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Enable and start
sudo systemctl daemon-reload
sudo systemctl enable oracle-load-generator
sudo systemctl start oracle-load-generator

# Check status
sudo systemctl status oracle-load-generator

# View logs
sudo journalctl -u oracle-load-generator -f

# Stop service
sudo systemctl stop oracle-load-generator
```

## Running in Screen (Keep Running After Disconnect)

```bash
# Install screen if not available
sudo yum install -y screen

# Start a screen session
screen -S oracle-load

# Run the load generator
java -cp .:ojdbc8.jar OracleLoadGenerator

# Detach from screen: Press Ctrl+A, then D

# Reattach later
screen -r oracle-load

# List all screen sessions
screen -ls

# Kill the session
screen -X -S oracle-load quit
```

## Running with nohup (Simple Background Process)

```bash
# Run in background
nohup java -cp .:ojdbc8.jar OracleLoadGenerator > load_generator.log 2>&1 &

# Check if running
ps aux | grep OracleLoadGenerator

# View logs
tail -f load_generator.log

# Stop the process
pkill -f OracleLoadGenerator
```

## Troubleshooting

### Issue: "javac: command not found"
```bash
sudo yum install -y java-11-openjdk-devel
export PATH=/usr/lib/jvm/java-11/bin:$PATH
```

### Issue: "wget: command not found"
```bash
sudo yum install -y wget
# Or use curl instead:
curl -O https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc8/21.7.0.0/ojdbc8-21.7.0.0.jar
mv ojdbc8-21.7.0.0.jar ojdbc8.jar
```

### Issue: "Cannot download ojdbc8.jar"
Manually download from your local machine and copy to Oracle Linux:
```bash
# On your Mac/Windows
# Download from: https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html

# Then copy to Oracle Linux
scp ojdbc8.jar user@your-oracle-linux-ip:~/
```

### Issue: Connection refused to Oracle DB
```bash
# Check connectivity
telnet 10.0.1.136 1521

# Check if Oracle listener is running on the DB server
# (On DB server): lsnrctl status

# Check firewall
sudo firewall-cmd --list-all
# If needed, allow port 1521:
# sudo firewall-cmd --permanent --add-port=1521/tcp
# sudo firewall-cmd --reload
```

### Issue: "ORA-01017: invalid username/password"
- Verify credentials in OracleLoadGenerator.java
- Make sure C##OTEL_MONITOR user exists and has necessary privileges

### Issue: Out of memory
```bash
# Increase heap size
java -Xmx2g -Xms512m -cp .:ojdbc8.jar OracleLoadGenerator
```

## Monitoring the Load

While running, connect to Oracle from another terminal:

```sql
sqlplus C##OTEL_MONITOR/MonitoR123##@10.0.1.136:1521/pdb2.privatetb.oracletb.oraclevcn.com

-- Check wait events
SELECT event, total_waits, time_waited 
FROM v$system_event 
WHERE wait_class != 'Idle' 
ORDER BY time_waited DESC 
FETCH FIRST 20 ROWS ONLY;

-- Check blocking sessions
SELECT blocking_session, sid, serial#, event, seconds_in_wait 
FROM v$session 
WHERE blocking_session IS NOT NULL;

-- Check active sessions
SELECT sid, serial#, username, status, event, seconds_in_wait
FROM v$session 
WHERE username = 'C##OTEL_MONITOR' 
ORDER BY seconds_in_wait DESC;

-- Check slow queries
SELECT sql_id, executions, elapsed_time/1000000 elapsed_sec,
       buffer_gets, disk_reads, rows_processed
FROM v$sql 
WHERE elapsed_time > 1000000 
ORDER BY elapsed_time DESC 
FETCH FIRST 20 ROWS ONLY;
```

## Stopping the Load Generator

- **Foreground**: Press `Ctrl+C`
- **Background (nohup)**: `pkill -f OracleLoadGenerator`
- **Screen**: Reattach with `screen -r oracle-load`, then `Ctrl+C`
- **Systemd**: `sudo systemctl stop oracle-load-generator`

The program will gracefully shutdown, releasing all locks and closing connections.
