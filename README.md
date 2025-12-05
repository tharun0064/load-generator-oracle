# Oracle Database Load Generator

This directory contains a comprehensive load generator for Oracle databases that creates various wait events, slow queries, and performance issues for testing monitoring systems.

## What's Inside

- **`OracleLoadGenerator.java`** - Single self-contained Java file (main program)
- **`setup_oracle_linux.sh`** - Automated setup script for Oracle Linux
- **`run_java.sh`** - Helper script to compile and run
- **`ORACLE_LINUX_GUIDE.md`** - Complete deployment guide for Oracle Linux
- **`JAVA_README.md`** - General Java deployment instructions

## Quick Start

### On Oracle Linux VM:

```bash
# Copy files to VM
scp -r load_generator user@your-oracle-linux-ip:~/

# SSH and run setup
ssh user@your-oracle-linux-ip
cd load_generator
chmod +x setup_oracle_linux.sh
./setup_oracle_linux.sh

# Start load generation
./start_load.sh
```

## What It Generates

### Continuous Load (Always Running):
- CPU-intensive queries (full scans, complex calculations)
- I/O-intensive operations (sorts, aggregations)
- Lock contention (blocking sessions)
- Enqueue waits (TX locks)
- Latch waits (shared pool)
- Buffer busy waits (hot blocks)
- Log file sync waits (redo generation)
- Direct path reads (parallel queries)
- DB file sequential reads (index lookups)

### Scheduled Load (Every 10 Seconds):
- Tablespace pressure
- Temp space usage
- Undo segment contention
- Library cache contention (50 unique SQLs)
- Row cache contention (dictionary cache)
- Checkpoint activity
- Archive log activity (heavy redo)
- Parse activity (100 hard parses)
- SQL*Net roundtrips (1000 network calls)
- Index contention (batch inserts)

## Database Connection

The load generator connects to:
- **Host**: `10.0.1.136:1521`
- **Service**: `pdb2.privatetb.oracletb.oraclevcn.com`
- **User**: `C##OTEL_MONITOR`
- **Password**: `MonitoR123##`

Edit these in `OracleLoadGenerator.java` if needed.

## Requirements

- Java 8 or higher
- Oracle JDBC driver (ojdbc8.jar or ojdbc11.jar)
- Network connectivity to Oracle database
- Oracle user with CREATE TABLE, INSERT, UPDATE, DELETE privileges

## Monitoring

While the load generator runs, monitor with:

```sql
-- Wait events
SELECT event, total_waits, time_waited 
FROM v$system_event 
WHERE wait_class != 'Idle' 
ORDER BY time_waited DESC;

-- Blocking sessions
SELECT blocking_session, sid, event, seconds_in_wait 
FROM v$session 
WHERE blocking_session IS NOT NULL;

-- Slow queries
SELECT sql_id, executions, elapsed_time/1000000 elapsed_sec 
FROM v$sql 
WHERE elapsed_time > 1000000 
ORDER BY elapsed_time DESC;
```

## Stopping

Press `Ctrl+C` for graceful shutdown. All locks will be released and connections closed.

For detailed instructions, see:
- **Oracle Linux**: `ORACLE_LINUX_GUIDE.md`
- **General Java**: `JAVA_README.md`
