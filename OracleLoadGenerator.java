import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Oracle Database Load Generator
 * 
 * Single-file Java program to generate various database loads and wait events.
 * 
 * Compile: javac OracleLoadGenerator.java
 * Run: java OracleLoadGenerator
 * 
 * Requirements:
 * - Oracle JDBC driver (ojdbc8.jar or ojdbc11.jar) in classpath
 * - Download from: https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html
 * - .env file with database credentials (see .env.example)
 * 
 * Run with: java -cp .:ojdbc8.jar OracleLoadGenerator
 * (On Windows: java -cp .;ojdbc8.jar OracleLoadGenerator)
 */
public class OracleLoadGenerator {
    
    private static final String JDBC_URL;
    private static final String USERNAME;
    private static final String PASSWORD;
    
    static {
        Map<String, String> env = loadEnvFile(".env");
        JDBC_URL = env.getOrDefault("ORACLE_JDBC_URL", "");
        USERNAME = env.getOrDefault("ORACLE_USERNAME", "");
        PASSWORD = env.getOrDefault("ORACLE_PASSWORD", "");
        
        if (JDBC_URL.isEmpty() || USERNAME.isEmpty() || PASSWORD.isEmpty()) {
            System.err.println("Error: Missing required environment variables in .env file");
            System.err.println("Please ensure .env file exists with ORACLE_JDBC_URL, ORACLE_USERNAME, and ORACLE_PASSWORD");
            System.exit(1);
        }
    }
    
    private final ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Random random = new Random();
    private Connection blockerConnection;
    private final List<Connection> blockedConnections = new ArrayList<>();
    
    public OracleLoadGenerator() {
        this.executorService = Executors.newFixedThreadPool(20);
    }
    
    private static Map<String, String> loadEnvFile(String filePath) {
        Map<String, String> envVars = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();
                    envVars.put(key, value);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading .env file: " + e.getMessage());
            System.err.println("Please create a .env file based on .env.example");
        }
        return envVars;
    }
    
    public static void main(String[] args) {
        System.out.println("Oracle Database Load Generator");
        System.out.println("===============================");
        System.out.println();
        
        OracleLoadGenerator generator = new OracleLoadGenerator();
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down gracefully...");
            generator.stop();
        }));
        
        try {
            generator.setupTestData();
            generator.start();
            
            System.out.println("\nLoad generation running. Press Ctrl+C to stop...");
            System.out.println("\nMonitor with these queries:");
            System.out.println("  SELECT event, total_waits, time_waited FROM v$system_event WHERE wait_class != 'Idle' ORDER BY time_waited DESC;");
            System.out.println("  SELECT blocking_session, sid, event, seconds_in_wait FROM v$session WHERE blocking_session IS NOT NULL;");
            System.out.println("  SELECT sql_id, executions, elapsed_time/1000000 elapsed_sec FROM v$sql WHERE elapsed_time > 1000000 ORDER BY elapsed_time DESC;");
            System.out.println();
            
            Thread.currentThread().join();
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            generator.stop();
        }
    }
    
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD);
    }
    
    private void setupTestData() throws SQLException {
        System.out.println("Setting up test tables and data...");
        
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            
            // Drop existing tables
            try {
                stmt.execute("BEGIN " +
                    "FOR t IN (SELECT table_name FROM user_tables WHERE table_name LIKE 'LOAD_TEST_%') LOOP " +
                    "EXECUTE IMMEDIATE 'DROP TABLE ' || t.table_name || ' PURGE'; " +
                    "END LOOP; " +
                    "END;");
            } catch (SQLException e) {
                // Ignore if no tables exist
            }
            
            // Drop existing sequences
            try {
                stmt.execute("BEGIN " +
                    "FOR s IN (SELECT sequence_name FROM user_sequences WHERE sequence_name IN ('LOAD_TEST_ORDER_SEQ', 'LOAD_TEST_LOCK_SEQ')) LOOP " +
                    "EXECUTE IMMEDIATE 'DROP SEQUENCE ' || s.sequence_name; " +
                    "END LOOP; " +
                    "END;");
            } catch (SQLException e) {
                // Ignore if no sequences exist
            }
            
            System.out.println("Creating tables...");
            
            stmt.execute("CREATE TABLE LOAD_TEST_ORDERS (" +
                "order_id NUMBER PRIMARY KEY, " +
                "customer_id NUMBER, " +
                "product_id NUMBER, " +
                "order_date DATE, " +
                "order_amount NUMBER(10,2), " +
                "status VARCHAR2(20), " +
                "region VARCHAR2(50), " +
                "sales_rep VARCHAR2(100), " +
                "comments VARCHAR2(4000), " +
                "lock_flag NUMBER DEFAULT 0, " +
                "created_date TIMESTAMP DEFAULT SYSTIMESTAMP)");
            
            stmt.execute("CREATE TABLE LOAD_TEST_LOCK_TARGET (" +
                "id NUMBER PRIMARY KEY, " +
                "data VARCHAR2(500), " +
                "counter NUMBER DEFAULT 0, " +
                "last_update TIMESTAMP DEFAULT SYSTIMESTAMP)");
            
            stmt.execute("CREATE SEQUENCE load_test_order_seq START WITH 1 INCREMENT BY 1");
            stmt.execute("CREATE SEQUENCE load_test_lock_seq START WITH 1 INCREMENT BY 1");
            
            System.out.println("Inserting test data (50,000 orders)...");
            
            stmt.execute("INSERT INTO LOAD_TEST_ORDERS " +
                "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                "SELECT " +
                "load_test_order_seq.NEXTVAL, " +
                "MOD(LEVEL, 1000) + 1, " +
                "MOD(LEVEL, 500) + 1, " +
                "SYSDATE - DBMS_RANDOM.VALUE(1, 365), " +
                "ROUND(DBMS_RANDOM.VALUE(10, 10000), 2), " +
                "CASE MOD(LEVEL, 4) " +
                "WHEN 0 THEN 'PENDING' " +
                "WHEN 1 THEN 'PROCESSING' " +
                "WHEN 2 THEN 'COMPLETED' " +
                "ELSE 'CANCELLED' END, " +
                "'Region' || MOD(LEVEL, 10), " +
                "'Rep' || MOD(LEVEL, 50), " +
                "RPAD('Order data ' || LEVEL, 1000, ' padding') " +
                "FROM dual CONNECT BY LEVEL <= 50000");
            
            stmt.execute("INSERT INTO LOAD_TEST_LOCK_TARGET (id, data, counter) " +
                "SELECT load_test_lock_seq.NEXTVAL, 'Lock target ' || LEVEL, 0 " +
                "FROM dual CONNECT BY LEVEL <= 100");
            
            conn.commit();
            
            System.out.println("Test data setup complete!");
        }
    }
    
    private void start() {
        System.out.println("Starting load generators...");
        
        executorService.submit(this::generateCPUIntensiveQueries);
        executorService.submit(this::generateIOIntensiveOperations);
        executorService.submit(this::generateLockContention);
        executorService.submit(this::generateEnqueueWaits);
        executorService.submit(this::generateLatchWaits);
        executorService.submit(this::generateLogFileWaits);
        executorService.submit(this::generateDirectPathReads);
        executorService.submit(this::generateDBFileSequentialReads);
        
        for (int i = 0; i < 10; i++) {
            final int workerId = i;
            executorService.submit(() -> generateBufferBusyWaits(workerId));
        }
        
        executorService.submit(this::scheduledMetricsGenerator);
        
        System.out.println("All load generators started successfully!");
    }
    
    private void scheduledMetricsGenerator() {
        System.out.println("Starting scheduled metrics generator (runs every 10 seconds)...");
        
        while (running.get()) {
            try {
                Thread.sleep(10000);
                
                System.out.println("\n=== Scheduled Metrics Burst ===");
                
                executorService.submit(this::generateTablespacePressure);
                executorService.submit(this::generateTempSpaceUsage);
                executorService.submit(this::generateUndoSegmentContention);
                executorService.submit(this::generateLibraryCacheContention);
                executorService.submit(this::generateRowCacheContention);
                executorService.submit(this::generateCheckpointActivity);
                executorService.submit(this::generateArchiveLogActivity);
                executorService.submit(this::generateParseActivity);
                executorService.submit(this::generateSQLNetActivity);
                executorService.submit(this::generateIndexContention);
                
                System.out.println("=== Metrics burst triggered ===\n");
                
            } catch (InterruptedException e) {
                if (running.get()) {
                    System.err.println("Scheduled generator interrupted: " + e.getMessage());
                }
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    private void generateTablespacePressure() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "INSERT INTO LOAD_TEST_ORDERS " +
                "SELECT load_test_order_seq.NEXTVAL, MOD(LEVEL, 1000), MOD(LEVEL, 500), " +
                "SYSDATE, 999.99, 'PENDING', 'Region0', 'Rep1', " +
                "RPAD('Tablespace pressure test', 3500, 'X') " +
                "FROM dual CONNECT BY LEVEL <= 2000");
            conn.commit();
            System.out.println("  [Scheduled] Generated tablespace pressure");
        } catch (SQLException e) {
            System.err.println("Tablespace pressure error: " + e.getMessage());
        }
    }
    
    private void generateTempSpaceUsage() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeQuery(
                "SELECT /*+ USE_HASH(o1 o2) */ " +
                "o1.order_id, o2.order_id, " +
                "RANK() OVER (ORDER BY o1.order_amount + o2.order_amount DESC) " +
                "FROM LOAD_TEST_ORDERS o1, LOAD_TEST_ORDERS o2 " +
                "WHERE o1.region = o2.region " +
                "ORDER BY o1.order_amount + o2.order_amount DESC");
            System.out.println("  [Scheduled] Generated temp space usage");
        } catch (SQLException e) {
            System.err.println("Temp space error: " + e.getMessage());
        }
    }
    
    private void generateUndoSegmentContention() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            stmt.executeUpdate(
                "UPDATE LOAD_TEST_ORDERS " +
                "SET order_amount = order_amount * 1.01, " +
                "comments = SUBSTR(comments, 1, 3000) || ' UNDO_TEST' " +
                "WHERE MOD(order_id, 100) = " + random.nextInt(100));
            Thread.sleep(2000);
            conn.rollback();
            System.out.println("  [Scheduled] Generated undo segment activity");
        } catch (Exception e) {
            System.err.println("Undo segment error: " + e.getMessage());
        }
    }
    
    private void generateLibraryCacheContention() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            for (int i = 0; i < 50; i++) {
                String dynamicSQL = "SELECT COUNT(*) FROM LOAD_TEST_ORDERS WHERE customer_id = " + 
                    random.nextInt(1000) + " AND region = 'Region" + random.nextInt(10) + "'";
                stmt.executeQuery(dynamicSQL);
            }
            System.out.println("  [Scheduled] Generated library cache contention (50 unique SQLs)");
        } catch (SQLException e) {
            System.err.println("Library cache error: " + e.getMessage());
        }
    }
    
    private void generateRowCacheContention() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            for (int i = 0; i < 30; i++) {
                stmt.executeQuery(
                    "SELECT table_name FROM user_tables WHERE table_name LIKE 'LOAD_TEST%'");
            }
            System.out.println("  [Scheduled] Generated row cache (dictionary cache) contention");
        } catch (SQLException e) {
            System.err.println("Row cache error: " + e.getMessage());
        }
    }
    
    private void generateCheckpointActivity() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "INSERT INTO LOAD_TEST_ORDERS " +
                "SELECT load_test_order_seq.NEXTVAL, LEVEL, LEVEL, SYSDATE, " +
                "DBMS_RANDOM.VALUE(1, 1000), 'PENDING', 'Region0', 'Rep1', " +
                "RPAD('Checkpoint test', 2000, 'Y') " +
                "FROM dual CONNECT BY LEVEL <= 5000");
            conn.commit();
            
            stmt.executeUpdate(
                "UPDATE LOAD_TEST_ORDERS SET status = 'PROCESSING' " +
                "WHERE status = 'PENDING' AND ROWNUM <= 3000");
            conn.commit();
            
            System.out.println("  [Scheduled] Generated checkpoint activity");
        } catch (SQLException e) {
            System.err.println("Checkpoint error: " + e.getMessage());
        }
    }
    
    private void generateArchiveLogActivity() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            for (int batch = 0; batch < 5; batch++) {
                stmt.executeUpdate(
                    "INSERT INTO LOAD_TEST_ORDERS " +
                    "SELECT load_test_order_seq.NEXTVAL, MOD(LEVEL, 500), MOD(LEVEL, 250), " +
                    "SYSDATE, 100, 'PENDING', 'Region0', 'Rep1', " +
                    "RPAD('Archive log generation', 1500, 'Z') " +
                    "FROM dual CONNECT BY LEVEL <= 1000");
                conn.commit();
            }
            System.out.println("  [Scheduled] Generated archive log activity (heavy redo)");
        } catch (SQLException e) {
            System.err.println("Archive log error: " + e.getMessage());
        }
    }
    
    private void generateParseActivity() {
        try (Connection conn = getConnection()) {
            for (int i = 0; i < 100; i++) {
                String uniqueSQL = "SELECT /* PARSE_TEST_" + System.currentTimeMillis() + "_" + i + 
                    " */ order_amount FROM LOAD_TEST_ORDERS WHERE order_id = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(uniqueSQL)) {
                    pstmt.setInt(1, random.nextInt(50000) + 1);
                    pstmt.executeQuery();
                }
            }
            System.out.println("  [Scheduled] Generated parse activity (100 hard parses)");
        } catch (SQLException e) {
            System.err.println("Parse activity error: " + e.getMessage());
        }
    }
    
    private void generateSQLNetActivity() {
        try (Connection conn = getConnection(); 
             PreparedStatement pstmt = conn.prepareStatement(
                 "SELECT order_id, customer_id, product_id, order_amount, status, region, comments " +
                 "FROM LOAD_TEST_ORDERS WHERE order_id = ?")) {
            
            for (int i = 0; i < 1000; i++) {
                pstmt.setInt(1, random.nextInt(50000) + 1);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        rs.getInt(1);
                        rs.getInt(2);
                        rs.getInt(3);
                        rs.getDouble(4);
                        rs.getString(5);
                        rs.getString(6);
                        rs.getString(7);
                    }
                }
            }
            System.out.println("  [Scheduled] Generated SQL*Net activity (1000 round-trips)");
        } catch (SQLException e) {
            System.err.println("SQL*Net activity error: " + e.getMessage());
        }
    }
    
    private void generateIndexContention() {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            
            try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO LOAD_TEST_ORDERS " +
                "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                "VALUES (load_test_order_seq.NEXTVAL, ?, ?, SYSDATE, ?, 'PENDING', 'Region0', 'Rep1', 'Index contention')")) {
                
                for (int i = 0; i < 500; i++) {
                    pstmt.setInt(1, random.nextInt(100) + 1);
                    pstmt.setInt(2, random.nextInt(50) + 1);
                    pstmt.setDouble(3, random.nextDouble() * 1000);
                    pstmt.addBatch();
                    
                    if (i % 100 == 0) {
                        pstmt.executeBatch();
                    }
                }
                pstmt.executeBatch();
                conn.commit();
            }
            
            System.out.println("  [Scheduled] Generated index contention (500 inserts on PK)");
        } catch (SQLException e) {
            System.err.println("Index contention error: " + e.getMessage());
        }
    }
    
    private void generateCPUIntensiveQueries() {
        String[] queries = {
            "SELECT /*+ FULL(o) */ " +
            "order_id, customer_id, " +
            "POWER(order_amount, 2) as amount_squared, " +
            "SQRT(order_amount) as amount_sqrt, " +
            "LN(order_amount + 1) as amount_log, " +
            "DBMS_RANDOM.VALUE(1, 1000000) as random_calc " +
            "FROM LOAD_TEST_ORDERS o " +
            "WHERE order_amount > (SELECT AVG(order_amount) * 0.8 FROM LOAD_TEST_ORDERS) " +
            "ORDER BY order_amount DESC",
            
            "SELECT /*+ USE_HASH(o1 o2) */ COUNT(*) " +
            "FROM LOAD_TEST_ORDERS o1, LOAD_TEST_ORDERS o2 " +
            "WHERE o1.region = o2.region " +
            "AND o1.status = o2.status " +
            "AND ROWNUM <= 10000",
            
            "SELECT o.order_id, o.customer_id, o.order_amount, " +
            "(SELECT COUNT(*) FROM LOAD_TEST_ORDERS o2 WHERE o2.customer_id = o.customer_id) as cust_order_count " +
            "FROM LOAD_TEST_ORDERS o " +
            "WHERE o.status = 'PENDING' " +
            "AND NOT EXISTS (SELECT 1 FROM LOAD_TEST_ORDERS o3 " +
            "WHERE o3.customer_id = o.customer_id AND o3.status = 'COMPLETED' " +
            "AND o3.order_date > o.order_date)"
        };
        
        while (running.get()) {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(queries[random.nextInt(queries.length)])) {
                while (rs.next() && running.get()) {
                    // Process results
                }
                System.out.println("Executed CPU-intensive query");
                Thread.sleep(2000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("CPU query error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateIOIntensiveOperations() {
        String[] queries = {
            "SELECT customer_id, COUNT(*) as order_count, " +
            "SUM(order_amount) as total_amount, " +
            "AVG(order_amount) as avg_amount, " +
            "RANK() OVER (ORDER BY SUM(order_amount) DESC) as customer_rank " +
            "FROM LOAD_TEST_ORDERS " +
            "GROUP BY customer_id " +
            "ORDER BY total_amount DESC",
            
            "SELECT region, status, COUNT(*) cnt, SUM(order_amount) total, " +
            "ROW_NUMBER() OVER (PARTITION BY region ORDER BY COUNT(*) DESC) as rn " +
            "FROM LOAD_TEST_ORDERS " +
            "GROUP BY region, status " +
            "ORDER BY region, cnt DESC",
            
            "INSERT INTO LOAD_TEST_ORDERS " +
            "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
            "SELECT load_test_order_seq.NEXTVAL, MOD(ROWNUM, 1000) + 1, MOD(ROWNUM, 500) + 1, " +
            "SYSDATE, ROUND(DBMS_RANDOM.VALUE(10, 1000), 2), 'PENDING', " +
            "'Region' || MOD(ROWNUM, 10), 'Rep' || MOD(ROWNUM, 50), RPAD('Bulk insert', 500, ' data') " +
            "FROM dual CONNECT BY LEVEL <= 1000"
        };
        
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                String query = queries[random.nextInt(queries.length)];
                if (query.startsWith("INSERT")) {
                    stmt.executeUpdate(query);
                    conn.commit();
                } else {
                    try (ResultSet rs = stmt.executeQuery(query)) {
                        while (rs.next() && running.get()) {
                            // Process results
                        }
                    }
                }
                System.out.println("Executed I/O-intensive operation");
                Thread.sleep(3000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("I/O query error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateLockContention() {
        try {
            Thread.sleep(2000);
            
            blockerConnection = getConnection();
            blockerConnection.setAutoCommit(false);
            
            System.out.println("Starting blocker session (will hold locks)");
            
            try (Statement stmt = blockerConnection.createStatement()) {
                stmt.executeUpdate(
                    "UPDATE LOAD_TEST_LOCK_TARGET " +
                    "SET counter = counter + 1, last_update = SYSTIMESTAMP, " +
                    "data = data || ' LOCKED' " +
                    "WHERE id BETWEEN 1 AND 50");
            }
            
            System.out.println("Blocker session acquired locks on rows 1-50");
            
            for (int i = 0; i < 5; i++) {
                final int sessionNum = i;
                Connection blockedConn = getConnection();
                blockedConn.setAutoCommit(false);
                blockedConnections.add(blockedConn);
                
                executorService.submit(() -> {
                    try {
                        System.out.println("Blocked session " + sessionNum + " attempting to acquire locks...");
                        try (Statement stmt = blockedConn.createStatement()) {
                            stmt.setQueryTimeout(30);
                            stmt.executeUpdate(
                                "UPDATE LOAD_TEST_LOCK_TARGET " +
                                "SET counter = counter + 100, data = data || ' BLOCKED_SESSION' " +
                                "WHERE id BETWEEN 25 AND 75");
                            blockedConn.commit();
                            System.out.println("Blocked session " + sessionNum + " acquired lock");
                        }
                    } catch (SQLException e) {
                        System.out.println("Blocked session " + sessionNum + " wait event: " + e.getMessage());
                    }
                });
            }
            
            Thread.sleep(20000);
            blockerConnection.rollback();
            System.out.println("Blocker session released locks");
            
        } catch (Exception e) {
            if (running.get()) {
                System.err.println("Lock contention error: " + e.getMessage());
            }
        }
    }
    
    private void generateEnqueueWaits() {
        while (running.get()) {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                int rowId = random.nextInt(100) + 1;
                
                try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE LOAD_TEST_LOCK_TARGET " +
                    "SET counter = counter + 1, last_update = SYSTIMESTAMP " +
                    "WHERE id = ?")) {
                    pstmt.setInt(1, rowId);
                    pstmt.executeUpdate();
                    conn.commit();
                }
                
                Thread.sleep(1000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Enqueue wait error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateLatchWaits() {
        while (running.get()) {
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO LOAD_TEST_ORDERS " +
                     "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                     "VALUES (load_test_order_seq.NEXTVAL, ?, ?, SYSDATE, ?, 'PENDING', 'Region0', 'Rep1', 'Latch contention test')")) {
                
                conn.setAutoCommit(false);
                pstmt.setInt(1, random.nextInt(1000) + 1);
                pstmt.setInt(2, random.nextInt(500) + 1);
                pstmt.setDouble(3, random.nextDouble() * 1000);
                pstmt.executeUpdate();
                conn.commit();
                
                Thread.sleep(500);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Latch wait error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateBufferBusyWaits(int workerId) {
        while (running.get()) {
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(
                     "UPDATE LOAD_TEST_LOCK_TARGET " +
                     "SET counter = counter + 1, data = SUBSTR('Worker' || ? || ' updated', 1, 100) " +
                     "WHERE id = ?")) {
                
                conn.setAutoCommit(false);
                int hotRowId = random.nextInt(10) + 1;
                pstmt.setInt(1, workerId);
                pstmt.setInt(2, hotRowId);
                pstmt.executeUpdate();
                conn.commit();
                
                Thread.sleep(300);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Worker " + workerId + " buffer busy wait: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateLogFileWaits() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                conn.setAutoCommit(false);
                stmt.executeUpdate(
                    "INSERT INTO LOAD_TEST_ORDERS " +
                    "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                    "SELECT load_test_order_seq.NEXTVAL, MOD(LEVEL, 1000) + 1, MOD(LEVEL, 500) + 1, " +
                    "SYSDATE, ROUND(DBMS_RANDOM.VALUE(10, 1000), 2), 'PENDING', " +
                    "'Region' || MOD(LEVEL, 10), 'Rep1', RPAD('Redo generation', 2000, ' heavy write load') " +
                    "FROM dual CONNECT BY LEVEL <= 500");
                conn.commit();
                
                Thread.sleep(200);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Log file wait error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateDirectPathReads() {
        while (running.get()) {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT /*+ FULL(o) PARALLEL(o, 4) */ " +
                     "order_id, customer_id, product_id, order_amount, comments " +
                     "FROM LOAD_TEST_ORDERS o " +
                     "WHERE order_amount > 100 " +
                     "ORDER BY order_amount DESC")) {
                
                while (rs.next() && running.get()) {
                    // Process results
                }
                System.out.println("Executed direct path read query");
                Thread.sleep(4000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Direct path read error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateDBFileSequentialReads() {
        while (running.get()) {
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT order_amount FROM LOAD_TEST_ORDERS WHERE order_id = ?")) {
                
                int orderId = random.nextInt(50000) + 1;
                pstmt.setInt(1, orderId);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        rs.getDouble(1);
                    }
                }
                
                Thread.sleep(1000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Sequential read error: " + e.getMessage());
                }
            }
        }
    }
    
    private void stop() {
        running.set(false);
        executorService.shutdownNow();
        
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("Executor did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        if (blockerConnection != null) {
            try {
                blockerConnection.rollback();
                blockerConnection.close();
            } catch (SQLException e) {
                System.err.println("Error closing blocker connection: " + e.getMessage());
            }
        }
        
        for (Connection conn : blockedConnections) {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e) {
                System.err.println("Error closing blocked connection: " + e.getMessage());
            }
        }
        
        System.out.println("Load generator stopped");
    }
}
