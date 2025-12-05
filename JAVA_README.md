# Oracle Load Generator - Java Version

## Quick Start on VM

### 1. Copy files to VM
```bash
scp OracleLoadGenerator.java user@your-vm-ip:~/
scp run_java.sh user@your-vm-ip:~/
```

### 2. Download Oracle JDBC Driver on VM
```bash
ssh user@your-vm-ip

# Download JDBC driver (choose one based on your Oracle version)
wget https://download.oracle.com/otn-pub/otn_software/jdbc/217/ojdbc8.jar

# Or for newer Oracle 21c
wget https://download.oracle.com/otn-pub/otn_software/jdbc/211/ojdbc11.jar
```

### 3. Run the load generator
```bash
chmod +x run_java.sh
./run_java.sh
```

## Manual Steps (if script doesn't work)

### Install Java (if not installed)
```bash
# RHEL/CentOS/Oracle Linux
sudo yum install java-11-openjdk-devel

# Ubuntu/Debian  
sudo apt install openjdk-11-jdk

# Verify
java -version
```

### Compile and Run
```bash
# Compile
javac -cp .:ojdbc8.jar OracleLoadGenerator.java

# Run
java -cp .:ojdbc8.jar OracleLoadGenerator

# On Windows, use semicolon instead of colon
javac -cp .;ojdbc8.jar OracleLoadGenerator.java
java -cp .;ojdbc8.jar OracleLoadGenerator
```

## What It Does

This single Java file creates:

- **Test tables** with 50,000+ order records
- **CPU-intensive queries** (full table scans, complex calculations, subqueries)
- **I/O-intensive operations** (sorts, aggregations, bulk inserts)
- **Lock contention** (blocker/blocked sessions)
- **Wait events:**
  - Enqueue waits (TX locks)
  - Latch waits (shared pool contention)
  - Buffer busy waits (hot block contention on same rows)
  - Log file sync waits (heavy redo generation)
  - Direct path reads (parallel queries)
  - DB file sequential reads (index lookups)

## Monitoring While Running

Connect to Oracle and run:

```sql
-- Check wait events
SELECT event, total_waits, time_waited 
FROM v$system_event 
WHERE wait_class != 'Idle' 
ORDER BY time_waited DESC;

-- Check blocking sessions  
SELECT blocking_session, sid, event, seconds_in_wait 
FROM v$session 
WHERE blocking_session IS NOT NULL;

-- Check slow queries
SELECT sql_id, executions, elapsed_time/1000000 elapsed_sec 
FROM v$sql 
WHERE elapsed_time > 1000000 
ORDER BY elapsed_time DESC;

-- Check active sessions
SELECT sid, serial#, username, status, event, seconds_in_wait
FROM v$session 
WHERE username = 'C##OTEL_MONITOR' 
ORDER BY seconds_in_wait DESC;
```

## Stopping

Press `Ctrl+C` to gracefully shutdown. The program will:
- Stop all worker threads
- Rollback any pending transactions
- Close all database connections

## Troubleshooting

**ClassNotFoundException: oracle.jdbc.driver.OracleDriver**
- JDBC driver not in classpath
- Make sure `ojdbc8.jar` or `ojdbc11.jar` is in the same directory
- Verify you're using `-cp .:ojdbc8.jar` when running

**SQLException: ORA-12154: TNS:could not resolve**
- Check database connection details in the code
- Verify network connectivity: `telnet 10.0.1.136 1521`

**OutOfMemoryError**
- Increase JVM heap: `java -Xmx2g -cp .:ojdbc8.jar OracleLoadGenerator`

**Connection timeout**
- Check firewall rules
- Verify Oracle listener is running
- Test with: `tnsping pdb2.privatetb.oracletb.oraclevcn.com`

## Configuration

Edit connection details in `OracleLoadGenerator.java`:

```java
private static final String JDBC_URL = "jdbc:oracle:thin:@10.0.1.136:1521/pdb2.privatetb.oracletb.oraclevcn.com";
private static final String USERNAME = "C##OTEL_MONITOR";
private static final String PASSWORD = "MonitoR123##";
```

## No External Dependencies

This is a **single self-contained Java file** with:
- No build tools needed (Maven/Gradle)
- No external libraries except Oracle JDBC driver
- Works on any Java 8+ JVM
- Easy to copy and run on any VM

Just copy the `.java` file and JDBC driver, compile, and run!
