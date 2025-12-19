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
        this.executorService = Executors.newFixedThreadPool(50);
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
            
            System.out.println("Inserting test data (100,000 orders)...");
            
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
                "FROM dual CONNECT BY LEVEL <= 100000");
            
            stmt.execute("INSERT INTO LOAD_TEST_LOCK_TARGET (id, data, counter) " +
                "SELECT load_test_lock_seq.NEXTVAL, 'Lock target ' || LEVEL, 0 " +
                "FROM dual CONNECT BY LEVEL <= 100");
            
            conn.commit();
            
            System.out.println("Test data setup complete!");
        }
    }
    
    private void start() {
        System.out.println("Starting load generators...");
        
        // Multiple instances of CPU intensive queries
        for (int i = 0; i < 3; i++) {
            executorService.submit(this::generateCPUIntensiveQueries);
        }
        
        // Multiple instances of I/O intensive operations
        for (int i = 0; i < 3; i++) {
            executorService.submit(this::generateIOIntensiveOperations);
        }
        
        executorService.submit(this::generateLockContention);
        
        // More enqueue and latch generators
        for (int i = 0; i < 5; i++) {
            executorService.submit(this::generateEnqueueWaits);
        }
        
        for (int i = 0; i < 3; i++) {
            executorService.submit(this::generateLatchWaits);
        }
        
        // More log file generators
        for (int i = 0; i < 3; i++) {
            executorService.submit(this::generateLogFileWaits);
        }
        
        for (int i = 0; i < 2; i++) {
            executorService.submit(this::generateDirectPathReads);
        }
        
        for (int i = 0; i < 3; i++) {
            executorService.submit(this::generateDBFileSequentialReads);
        }
        
        // Increase buffer busy wait workers from 10 to 20
        for (int i = 0; i < 20; i++) {
            final int workerId = i;
            executorService.submit(() -> generateBufferBusyWaits(workerId));
        }
        
        // New scenarios
        for (int i = 0; i < 2; i++) {
            executorService.submit(this::generateDeadlockScenarios);
        }
        
        for (int i = 0; i < 2; i++) {
            executorService.submit(this::generateParallelQueryLoad);
        }
        
        executorService.submit(this::generateLOBOperations);
        executorService.submit(this::generatePLSQLLoad);
        executorService.submit(this::generateBindVariableLoad);
        executorService.submit(this::generateFlashbackQueries);
        
        for (int i = 0; i < 2; i++) {
            executorService.submit(this::generateDirectPathWrites);
        }
        
        executorService.submit(this::generatePartitionPruningTests);
        executorService.submit(this::generateMaterializedViewRefresh);
        executorService.submit(this::generateCursorLeaks);
        executorService.submit(this::generateTransactionRollbackPatterns);
        executorService.submit(this::generateSortSpillOperations);
        executorService.submit(this::generateHashJoinOperations);
        executorService.submit(this::generateNestedLoopJoins);
        executorService.submit(this::generateBitmapIndexOperations);
        executorService.submit(this::generateGlobalTempTableOps);
        executorService.submit(this::generateTableFragmentation);
        executorService.submit(this::generateAuditTrailLoad);

        executorService.submit(this::scheduledMetricsGenerator);

        System.out.println("[MAIN] Submitting cleanup thread...");
        System.out.flush();
        executorService.submit(() -> {
            System.out.println("[CLEANUP] Thread started!");
            System.out.flush();
            continuousTableCleanup();
        });

        System.out.println("[MAIN] Submitting status reporter thread...");
        System.out.flush();
        executorService.submit(() -> {
            System.out.println("[STATUS] Thread started!");
            System.out.flush();
            periodicStatusReport();
        });

        System.out.println("All load generators started successfully!");
        System.out.println("Status reports will appear every 30 seconds...");
        System.out.flush();
    }
    
    private void scheduledMetricsGenerator() {
        while (running.get()) {
            try {
                Thread.sleep(5000);

                // Existing metrics
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
                
                // Enhanced wait events and blocking sessions
                executorService.submit(this::generateEnqueueContentionBurst);
                executorService.submit(this::generateRowLevelLocking);
                executorService.submit(this::generateBufferCacheContention);
                executorService.submit(this::generateSequenceContention);
                executorService.submit(this::generateDBFileSyncWaits);
                executorService.submit(this::generateControlFileWaits);
                executorService.submit(this::generateLibraryCachePinWaits);
                executorService.submit(this::generateRowCacheLockWaits);

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
            conn.setAutoCommit(false);
            stmt.executeUpdate(
                "INSERT INTO LOAD_TEST_ORDERS " +
                "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                "SELECT load_test_order_seq.NEXTVAL, MOD(LEVEL, 100), MOD(LEVEL, 50), " +
                "SYSDATE, 999.99, 'PENDING', 'Region0', 'Rep1', " +
                "RPAD('Tablespace pressure test', 3500, 'X') " +
                "FROM dual CONNECT BY LEVEL <= 5000");
            conn.commit();
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
        } catch (SQLException e) {
            System.err.println("Temp space error: " + e.getMessage());
        }
    }
    
    private void generateUndoSegmentContention() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            stmt.executeUpdate(
                "UPDATE LOAD_TEST_ORDERS " +
                "SET order_amount = 250.50, " +
                "comments = 'UNDO_SEGMENT_TEST' " +
                "WHERE MOD(order_id, 100) = 50");
            Thread.sleep(2000);
            conn.rollback();
        } catch (Exception e) {
            System.err.println("Undo segment error: " + e.getMessage());
        }
    }
    
    private void generateLibraryCacheContention() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            for (int i = 0; i < 50; i++) {
                String dynamicSQL = "SELECT COUNT(*) FROM LOAD_TEST_ORDERS WHERE customer_id = 500 AND region = 'Region5'";
                stmt.executeQuery(dynamicSQL);
            }
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
        } catch (SQLException e) {
            System.err.println("Row cache error: " + e.getMessage());
        }
    }
    
    private void generateCheckpointActivity() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            stmt.executeUpdate(
                "INSERT INTO LOAD_TEST_ORDERS " +
                "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                "SELECT load_test_order_seq.NEXTVAL, MOD(LEVEL, 100), MOD(LEVEL, 50), SYSDATE, " +
                "100.00, 'PENDING', 'Region0', 'Rep1', " +
                "RPAD('Checkpoint test', 2000, 'Y') " +
                "FROM dual CONNECT BY LEVEL <= 10000");
            conn.commit();
            
            stmt.executeUpdate(
                "UPDATE LOAD_TEST_ORDERS SET status = 'PROCESSING' " +
                "WHERE status = 'PENDING' AND ROWNUM <= 3000");
            conn.commit();
        } catch (SQLException e) {
            System.err.println("Checkpoint error: " + e.getMessage());
        }
    }
    
    private void generateArchiveLogActivity() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            for (int batch = 0; batch < 10; batch++) {
                stmt.executeUpdate(
                    "INSERT INTO LOAD_TEST_ORDERS " +
                    "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                    "SELECT load_test_order_seq.NEXTVAL, MOD(LEVEL, 500), MOD(LEVEL, 250), " +
                    "SYSDATE, 100, 'PENDING', 'Region0', 'Rep1', " +
                    "RPAD('Archive log generation', 1500, 'Z') " +
                    "FROM dual CONNECT BY LEVEL <= 2000");
                conn.commit();
            }
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
                    pstmt.setInt(1, 25000);
                    pstmt.executeQuery();
                }
            }
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
                pstmt.setInt(1, 25000);
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
                
                for (int i = 0; i < 1000; i++) {
                    pstmt.setInt(1, 50);
                    pstmt.setInt(2, 25);
                    pstmt.setDouble(3, 500.00);
                    pstmt.addBatch();
                    
                    if (i % 100 == 0) {
                        pstmt.executeBatch();
                    }
                }
                pstmt.executeBatch();
                conn.commit();
            }
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
                Thread.sleep(500);
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
            "SELECT load_test_order_seq.NEXTVAL, MOD(LEVEL, 100) + 1, MOD(LEVEL, 50) + 1, " +
            "SYSDATE, 100.00, 'PENDING', " +
            "'Region' || MOD(LEVEL, 10), 'Rep' || MOD(LEVEL, 50), RPAD('Bulk insert', 500, ' data') " +
            "FROM dual CONNECT BY LEVEL <= 1000"
        };
        
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                conn.setAutoCommit(false);
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
                Thread.sleep(1500);
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

            try (Statement stmt = blockerConnection.createStatement()) {
                stmt.executeUpdate(
                    "UPDATE LOAD_TEST_LOCK_TARGET " +
                    "SET counter = 200, last_update = SYSTIMESTAMP, " +
                    "data = 'LOCKED' " +
                    "WHERE id BETWEEN 1 AND 50");
            }

            for (int i = 0; i < 5; i++) {
                Connection blockedConn = getConnection();
                blockedConn.setAutoCommit(false);
                blockedConnections.add(blockedConn);

                executorService.submit(() -> {
                    try {
                        try (Statement stmt = blockedConn.createStatement()) {
                            stmt.setQueryTimeout(30);
                            stmt.executeUpdate(
                                "UPDATE LOAD_TEST_LOCK_TARGET " +
                                "SET counter = 300, data = 'BLOCKED_SESSION' " +
                                "WHERE id BETWEEN 25 AND 75");
                            blockedConn.commit();
                        }
                    } catch (SQLException e) {
                        // Lock contention expected
                    }
                });
            }

            Thread.sleep(20000);
            blockerConnection.rollback();

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
                int rowId = 50;
                
                try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE LOAD_TEST_LOCK_TARGET " +
                    "SET counter = 150, last_update = SYSTIMESTAMP " +
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
                pstmt.setInt(1, 500);
                pstmt.setInt(2, 250);
                pstmt.setDouble(3, 500.00);
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
                     "SET counter = 250, data = 'BUFFER_BUSY' " +
                     "WHERE id = ?")) {
                
                conn.setAutoCommit(false);
                int hotRowId = 5;
                pstmt.setInt(1, hotRowId);
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
                
                int orderId = 25000;
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
    
    private void generateEnqueueContentionBurst() {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            
            // Create heavy enqueue contention by updating multiple rows
            try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE LOAD_TEST_LOCK_TARGET " +
                "SET counter = 400, data = 'ENQUEUE_BURST' " +
                "WHERE id BETWEEN ? AND ?")) {
                
                int startId = 25;
                pstmt.setInt(1, startId);
                pstmt.setInt(2, startId + 20);
                pstmt.executeUpdate();
                
                // Hold the lock for a bit to create contention
                Thread.sleep(5000);
                conn.commit();
            }
        } catch (Exception e) {
            System.err.println("Enqueue contention burst error: " + e.getMessage());
        }
    }
    
    private void generateRowLevelLocking() {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            
            // Lock specific rows to create blocking sessions
            try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT * FROM LOAD_TEST_ORDERS WHERE order_id BETWEEN ? AND ? FOR UPDATE")) {
                
                int startId = 20000;
                pstmt.setInt(1, startId);
                pstmt.setInt(2, startId + 100);
                pstmt.executeQuery();
                
                // Hold locks to create blocking
                Thread.sleep(8000);
                conn.rollback();
            }
        } catch (Exception e) {
            System.err.println("Row-level locking error: " + e.getMessage());
        }
    }
    
    private void generateBufferCacheContention() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            
            // Force buffer cache contention with concurrent updates to hot blocks
            for (int i = 0; i < 10; i++) {
                int hotRowId = 5;
                stmt.executeUpdate(
                    "UPDATE LOAD_TEST_LOCK_TARGET " +
                    "SET counter = 350, last_update = SYSTIMESTAMP " +
                    "WHERE id = " + hotRowId);
            }
            conn.commit();
        } catch (SQLException e) {
            System.err.println("Buffer cache contention error: " + e.getMessage());
        }
    }
    
    private void generateSequenceContention() {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            
            // Rapid sequence access to create sequence cache contention
            try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO LOAD_TEST_ORDERS " +
                "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                "VALUES (load_test_order_seq.NEXTVAL, ?, ?, SYSDATE, ?, 'PENDING', 'Region0', 'Rep1', 'Sequence test')")) {
                
                for (int i = 0; i < 500; i++) {
                    pstmt.setInt(1, 500);
                    pstmt.setInt(2, 250);
                    pstmt.setDouble(3, 500.00);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                conn.commit();
            }
        } catch (SQLException e) {
            System.err.println("Sequence contention error: " + e.getMessage());
        }
    }
    
    private void generateDBFileSyncWaits() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            
            // Generate sync waits with large data modifications
            stmt.executeUpdate(
                "INSERT INTO LOAD_TEST_ORDERS " +
                "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                "SELECT load_test_order_seq.NEXTVAL, MOD(LEVEL, 100), MOD(LEVEL, 50), " +
                "SYSDATE, 500.00, 'PENDING', " +
                "'Region' || MOD(LEVEL, 10), 'Rep' || MOD(LEVEL, 50), " +
                "RPAD('DB file sync test', 2500, 'X') " +
                "FROM dual CONNECT BY LEVEL <= 5000");
            conn.commit();

            // Force checkpoint
            stmt.execute("ALTER SYSTEM CHECKPOINT");
        } catch (SQLException e) {
            System.err.println("DB file sync error: " + e.getMessage());
        }
    }
    
    private void generateControlFileWaits() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Generate control file sequential reads
            for (int i = 0; i < 20; i++) {
                stmt.executeQuery(
                    "SELECT name, value FROM v$parameter WHERE name LIKE '%control%'");
                stmt.executeQuery(
                    "SELECT status FROM v$instance");
            }
        } catch (SQLException e) {
            System.err.println("Control file waits error: " + e.getMessage());
        }
    }
    
    private void generateLibraryCachePinWaits() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Create library cache pin waits with PL/SQL compilation
            for (int i = 0; i < 15; i++) {
                String procName = "TEMP_PROC_" + System.currentTimeMillis() + "_" + i;
                try {
                    stmt.execute(
                        "CREATE OR REPLACE PROCEDURE " + procName + " AS " +
                        "BEGIN " +
                        "  FOR i IN 1..10 LOOP " +
                        "    NULL; " +
                        "  END LOOP; " +
                        "END;");
                    
                    stmt.execute("DROP PROCEDURE " + procName);
                } catch (SQLException e) {
                    // Ignore errors for temp procedures
                }
            }
        } catch (SQLException e) {
            System.err.println("Library cache pin error: " + e.getMessage());
        }
    }
    
    private void generateRowCacheLockWaits() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Generate row cache lock waits with metadata queries
            for (int i = 0; i < 50; i++) {
                stmt.executeQuery(
                    "SELECT table_name, tablespace_name, num_rows FROM user_tables WHERE table_name LIKE 'LOAD_TEST%'");
                stmt.executeQuery(
                    "SELECT sequence_name, min_value, max_value, increment_by FROM user_sequences WHERE sequence_name LIKE 'LOAD_TEST%'");
                stmt.executeQuery(
                    "SELECT index_name, uniqueness FROM user_indexes WHERE table_name LIKE 'LOAD_TEST%'");
            }
        } catch (SQLException e) {
            System.err.println("Row cache lock error: " + e.getMessage());
        }
    }
    
    private void generateDeadlockScenarios() {
        while (running.get()) {
            try (Connection conn1 = getConnection(); Connection conn2 = getConnection()) {
                conn1.setAutoCommit(false);
                conn2.setAutoCommit(false);
                
                // Thread 1: Lock row 1, then try to lock row 2
                try (Statement stmt1 = conn1.createStatement()) {
                    stmt1.executeUpdate("UPDATE LOAD_TEST_LOCK_TARGET SET counter = 500 WHERE id = 1");
                    Thread.sleep(100);
                    
                    // Thread 2: Lock row 2, then try to lock row 1
                    executorService.submit(() -> {
                        try (Statement stmt2 = conn2.createStatement()) {
                            stmt2.executeUpdate("UPDATE LOAD_TEST_LOCK_TARGET SET counter = 600 WHERE id = 2");
                            Thread.sleep(100);
                            stmt2.executeUpdate("UPDATE LOAD_TEST_LOCK_TARGET SET counter = 700 WHERE id = 1");
                            conn2.commit();
                        } catch (Exception e) {
                            try { conn2.rollback(); } catch (SQLException sqle) {}
                        }
                    });
                    
                    stmt1.executeUpdate("UPDATE LOAD_TEST_LOCK_TARGET SET counter = 800 WHERE id = 2");
                    conn1.commit();
                }
                Thread.sleep(5000);
            } catch (SQLException e) {
                // ORA-00060: Deadlock is expected and intentional for this generator
                if (running.get() && e.getErrorCode() != 60) {
                    System.err.println("Deadlock scenario error: " + e.getMessage());
                }
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Deadlock scenario error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateParallelQueryLoad() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeQuery(
                    "SELECT /*+ PARALLEL(o, 8) FULL(o) */ " +
                    "customer_id, product_id, " +
                    "SUM(order_amount) as total, " +
                    "AVG(order_amount) as avg_amt, " +
                    "COUNT(*) as cnt, " +
                    "STDDEV(order_amount) as stddev_amt " +
                    "FROM LOAD_TEST_ORDERS o " +
                    "GROUP BY customer_id, product_id " +
                    "HAVING COUNT(*) > 1 " +
                    "ORDER BY total DESC");
                Thread.sleep(3000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Parallel query error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateLOBOperations() {
        while (running.get()) {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                
                // Write large LOB data
                try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE LOAD_TEST_ORDERS SET comments = ? WHERE order_id = 50000")) {
                    String largeLob = new String(new char[3500]).replace('\0', 'L');
                    pstmt.setString(1, largeLob);
                    pstmt.executeUpdate();
                    conn.commit();
                }
                
                // Read LOB data
                try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT comments FROM LOAD_TEST_ORDERS WHERE LENGTH(comments) > 1000 AND ROWNUM <= 100");
                     ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next() && running.get()) {
                        rs.getString(1);
                    }
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("LOB operations error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generatePLSQLLoad() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // Create a test procedure if it doesn't exist
                try {
                    stmt.execute(
                        "CREATE OR REPLACE PROCEDURE LOAD_TEST_PROC IS " +
                        "  v_count NUMBER; " +
                        "  v_sum NUMBER; " +
                        "BEGIN " +
                        "  FOR i IN 1..100 LOOP " +
                        "    BEGIN " +
                        "      SELECT COUNT(*), SUM(order_amount) INTO v_count, v_sum " +
                        "      FROM LOAD_TEST_ORDERS WHERE customer_id = MOD(i, 100); " +
                        "      UPDATE LOAD_TEST_LOCK_TARGET SET counter = v_count " +
                        "      WHERE id = MOD(i, 10) + 1; " +
                        "      COMMIT; " +
                        "    EXCEPTION " +
                        "      WHEN OTHERS THEN " +
                        "        ROLLBACK; " +
                        "        NULL; " +
                        "    END; " +
                        "  END LOOP; " +
                        "END;");
                } catch (SQLException e) {
                    // Check for compilation errors
                    if (e.getErrorCode() == 6550) {
                        // PL/SQL compilation error - log once and skip procedure creation
                        System.err.println("Warning: Could not compile LOAD_TEST_PROC: " + e.getMessage());
                        Thread.sleep(60000); // Wait a minute before retrying
                        return;
                    }
                }

                // Execute the procedure
                try (CallableStatement cstmt = conn.prepareCall("BEGIN LOAD_TEST_PROC; END;")) {
                    cstmt.execute();
                }
                Thread.sleep(4000);
            } catch (SQLException e) {
                // ORA-00060: Deadlock, ORA-06550: compilation error
                if (running.get() && e.getErrorCode() != 60 && e.getErrorCode() != 6550) {
                    System.err.println("PL/SQL load error: " + e.getMessage());
                }
                // If procedure is invalid, wait a bit before retrying
                if (e.getErrorCode() == 6550) {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("PL/SQL load error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateBindVariableLoad() {
        while (running.get()) {
            try (Connection conn = getConnection()) {
                // Use bind variables for better cursor sharing
                try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT order_id, customer_id, order_amount, status " +
                    "FROM LOAD_TEST_ORDERS " +
                    "WHERE customer_id = ? AND status = ? AND order_amount > ?")) {
                    
                    for (int i = 0; i < 50; i++) {
                        pstmt.setInt(1, 500);
                        pstmt.setString(2, "PENDING");
                        pstmt.setDouble(3, 100.00);
                        
                        try (ResultSet rs = pstmt.executeQuery()) {
                            while (rs.next() && running.get()) {
                                rs.getInt(1);
                            }
                        }
                    }
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Bind variable load error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateFlashbackQueries() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                try {
                    // Query data as of 1 minute ago (reduced from 5 to avoid table definition issues)
                    stmt.executeQuery(
                        "SELECT order_id, customer_id, order_amount, status " +
                        "FROM LOAD_TEST_ORDERS " +
                        "AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '1' MINUTE) " +
                        "WHERE customer_id = 500 " +
                        "AND ROWNUM <= 100");
                } catch (SQLException e) {
                    // ORA-01466: table definition changed, ORA-08181: snapshot too old
                    if (e.getErrorCode() != 1466 && e.getErrorCode() != 8181) {
                        throw e;
                    }
                    // Silently skip if table is too new or definition changed
                }

                try {
                    // Query versions of a specific row - limited time window
                    stmt.executeQuery(
                        "SELECT versions_starttime, versions_endtime, order_amount, status " +
                        "FROM LOAD_TEST_ORDERS " +
                        "VERSIONS BETWEEN TIMESTAMP (SYSTIMESTAMP - INTERVAL '5' MINUTE) AND SYSTIMESTAMP " +
                        "WHERE order_id = 50000");
                } catch (SQLException e) {
                    // ORA-01466: table definition changed, ORA-08181: snapshot too old
                    if (e.getErrorCode() != 1466 && e.getErrorCode() != 8181) {
                        throw e;
                    }
                    // Silently skip if table is too new or definition changed
                }

                Thread.sleep(6000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Flashback query error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateDirectPathWrites() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                conn.setAutoCommit(false);
                
                // Use APPEND hint for direct path writes
                stmt.executeUpdate(
                    "INSERT /*+ APPEND */ INTO LOAD_TEST_ORDERS " +
                    "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                    "SELECT load_test_order_seq.NEXTVAL, MOD(LEVEL, 100), MOD(LEVEL, 50), " +
                    "SYSDATE, 150.00, 'PENDING', 'Region0', 'Rep1', " +
                    "RPAD('Direct path write test', 1000, 'D') " +
                    "FROM dual CONNECT BY LEVEL <= 1000");

                conn.commit();
                Thread.sleep(5000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Direct path write error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generatePartitionPruningTests() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // Simulate partition access patterns
                stmt.executeQuery(
                    "SELECT /*+ FULL(o) */ " +
                    "customer_id, region, COUNT(*) as cnt, SUM(order_amount) as total " +
                    "FROM LOAD_TEST_ORDERS o " +
                    "WHERE region = 'Region5' " +
                    "AND order_date >= SYSDATE - 30 " +
                    "GROUP BY customer_id, region " +
                    "ORDER BY total DESC");
                
                // Range scan simulation
                stmt.executeQuery(
                    "SELECT order_id, customer_id, order_amount " +
                    "FROM LOAD_TEST_ORDERS " +
                    "WHERE order_date BETWEEN SYSDATE - 60 AND SYSDATE - 30 " +
                    "AND status = 'COMPLETED' " +
                    "ORDER BY order_date");
                Thread.sleep(4000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Partition pruning error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateMaterializedViewRefresh() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // Create materialized view if it doesn't exist
                try {
                    stmt.execute(
                        "CREATE MATERIALIZED VIEW LOAD_TEST_MV " +
                        "BUILD IMMEDIATE REFRESH COMPLETE ON DEMAND AS " +
                        "SELECT region, status, COUNT(*) as order_count, " +
                        "SUM(order_amount) as total_amount, " +
                        "AVG(order_amount) as avg_amount " +
                        "FROM LOAD_TEST_ORDERS " +
                        "GROUP BY region, status");
                } catch (SQLException e) {
                    // MV might already exist
                }
                
                // Refresh the materialized view
                stmt.execute("BEGIN DBMS_MVIEW.REFRESH('LOAD_TEST_MV', 'C'); END;");
                
                // Query the materialized view
                stmt.executeQuery(
                    "SELECT * FROM LOAD_TEST_MV " +
                    "WHERE order_count > 10 " +
                    "ORDER BY total_amount DESC");
                Thread.sleep(10000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Materialized view error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateCursorLeaks() {
        while (running.get()) {
            try (Connection conn = getConnection()) {
                // Simulate cursor leaks by opening many cursors
                for (int i = 0; i < 50; i++) {
                    PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT order_id, customer_id FROM LOAD_TEST_ORDERS WHERE customer_id = ?");
                    pstmt.setInt(1, 500);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) {
                        rs.getInt(1);
                    }
                    // Intentionally not closing to simulate leak
                    // They will be closed when connection closes
                }
                Thread.sleep(5000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Cursor leak error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateTransactionRollbackPatterns() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                conn.setAutoCommit(false);
                
                // Large transaction that will be rolled back
                stmt.executeUpdate(
                    "UPDATE LOAD_TEST_ORDERS " +
                    "SET order_amount = 999.99, status = 'CANCELLED' " +
                    "WHERE MOD(order_id, 10) = 5");
                
                stmt.executeUpdate(
                    "INSERT INTO LOAD_TEST_ORDERS " +
                    "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                    "SELECT load_test_order_seq.NEXTVAL, 100, 50, SYSDATE, 200.00, 'PENDING', " +
                    "'Region0', 'Rep1', 'Rollback test' " +
                    "FROM dual CONNECT BY LEVEL <= 500");
                
                // Rollback the transaction
                Thread.sleep(2000);
                conn.rollback();
                Thread.sleep(3000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Transaction rollback error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateSortSpillOperations() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // Force sorts that may spill to disk
                stmt.executeQuery(
                    "SELECT /*+ USE_HASH(o1 o2) */ " +
                    "o1.customer_id, o2.product_id, " +
                    "o1.order_amount + o2.order_amount as combined, " +
                    "RANK() OVER (PARTITION BY o1.region ORDER BY o1.order_amount DESC) as rnk " +
                    "FROM LOAD_TEST_ORDERS o1, LOAD_TEST_ORDERS o2 " +
                    "WHERE o1.status = o2.status " +
                    "AND ROWNUM <= 50000 " +
                    "ORDER BY combined DESC, o1.customer_id, o2.product_id");
                Thread.sleep(5000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Sort spill error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateHashJoinOperations() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // Force hash joins
                stmt.executeQuery(
                    "SELECT /*+ USE_HASH(o l) */ " +
                    "o.order_id, o.customer_id, o.order_amount, " +
                    "l.id, l.counter " +
                    "FROM LOAD_TEST_ORDERS o " +
                    "JOIN LOAD_TEST_LOCK_TARGET l ON MOD(o.customer_id, 100) = l.id " +
                    "WHERE o.status = 'PENDING' " +
                    "AND o.order_amount > 100.00");
                Thread.sleep(3000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Hash join error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateNestedLoopJoins() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // Force nested loop joins
                stmt.executeQuery(
                    "SELECT /*+ USE_NL(o l) */ " +
                    "o.order_id, o.customer_id, o.order_amount, " +
                    "l.data, l.counter " +
                    "FROM LOAD_TEST_ORDERS o, LOAD_TEST_LOCK_TARGET l " +
                    "WHERE o.customer_id = l.id " +
                    "AND o.order_id BETWEEN 1000 AND 2000 " +
                    "AND l.id <= 50");
                Thread.sleep(2500);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Nested loop join error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateBitmapIndexOperations() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // Create bitmap index if it doesn't exist
                try {
                    stmt.execute(
                        "CREATE BITMAP INDEX LOAD_TEST_STATUS_BITMAP " +
                        "ON LOAD_TEST_ORDERS(status)");
                    stmt.execute(
                        "CREATE BITMAP INDEX LOAD_TEST_REGION_BITMAP " +
                        "ON LOAD_TEST_ORDERS(region)");
                } catch (SQLException e) {
                    // Indexes might already exist
                }
                
                // Queries that benefit from bitmap indexes
                stmt.executeQuery(
                    "SELECT COUNT(*) " +
                    "FROM LOAD_TEST_ORDERS " +
                    "WHERE status IN ('PENDING', 'PROCESSING') " +
                    "AND region IN ('Region0', 'Region1', 'Region2')");
                
                stmt.executeQuery(
                    "SELECT status, region, COUNT(*) as cnt " +
                    "FROM LOAD_TEST_ORDERS " +
                    "WHERE status IN ('COMPLETED', 'CANCELLED') " +
                    "GROUP BY status, region " +
                    "HAVING COUNT(*) > 10");
                Thread.sleep(4000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Bitmap index error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateGlobalTempTableOps() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // Create global temp table if it doesn't exist
                try {
                    stmt.execute(
                        "CREATE GLOBAL TEMPORARY TABLE LOAD_TEST_GTT ( " +
                        "session_id NUMBER, " +
                        "customer_id NUMBER, " +
                        "total_amount NUMBER, " +
                        "order_count NUMBER) " +
                        "ON COMMIT DELETE ROWS");
                } catch (SQLException e) {
                    // GTT might already exist
                }
                
                conn.setAutoCommit(false);
                
                // Populate GTT
                stmt.executeUpdate(
                    "INSERT INTO LOAD_TEST_GTT " +
                    "SELECT 1 as session_id, customer_id, " +
                    "SUM(order_amount) as total, COUNT(*) as cnt " +
                    "FROM LOAD_TEST_ORDERS " +
                    "WHERE status = 'COMPLETED' " +
                    "GROUP BY customer_id " +
                    "HAVING COUNT(*) > 5");
                
                // Query GTT
                stmt.executeQuery(
                    "SELECT customer_id, total_amount, order_count " +
                    "FROM LOAD_TEST_GTT " +
                    "WHERE total_amount > 1000.00 " +
                    "ORDER BY total_amount DESC");

                conn.commit();
                Thread.sleep(3000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Global temp table error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateTableFragmentation() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                conn.setAutoCommit(false);
                
                // Create fragmentation with random deletes and inserts
                stmt.executeUpdate(
                    "DELETE FROM LOAD_TEST_ORDERS " +
                    "WHERE MOD(order_id, 100) = 77 " +
                    "AND ROWNUM <= 100");
                
                stmt.executeUpdate(
                    "INSERT INTO LOAD_TEST_ORDERS " +
                    "(order_id, customer_id, product_id, order_date, order_amount, status, region, sales_rep, comments) " +
                    "SELECT load_test_order_seq.NEXTVAL, MOD(LEVEL, 100), MOD(LEVEL, 50), " +
                    "SYSDATE, 175.00, 'PENDING', 'Region' || MOD(LEVEL, 10), 'Rep1', " +
                    "RPAD('Fragmentation test', 800, 'F') " +
                    "FROM dual CONNECT BY LEVEL <= 150");
                
                conn.commit();
                
                // Force full table scan to read fragmented blocks
                stmt.executeQuery(
                    "SELECT /*+ FULL(o) */ COUNT(*), AVG(order_amount) " +
                    "FROM LOAD_TEST_ORDERS o " +
                    "WHERE status <> 'ARCHIVED'");
                Thread.sleep(5000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Table fragmentation error: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateAuditTrailLoad() {
        while (running.get()) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // Simulate audit trail queries
                stmt.executeQuery(
                    "SELECT username, action_name, obj_name, extended_timestamp " +
                    "FROM dba_audit_trail " +
                    "WHERE username = USER " +
                    "AND extended_timestamp > SYSTIMESTAMP - INTERVAL '1' HOUR " +
                    "AND ROWNUM <= 100");
                
                // Query unified audit trail
                try {
                    stmt.executeQuery(
                        "SELECT dbusername, event_timestamp, action_name, object_name " +
                        "FROM unified_audit_trail " +
                        "WHERE dbusername = USER " +
                        "AND event_timestamp > SYSTIMESTAMP - INTERVAL '1' HOUR " +
                        "AND ROWNUM <= 100");
                } catch (SQLException e) {
                    // Unified audit might not be enabled
                }
                Thread.sleep(8000);
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("Audit trail error: " + e.getMessage());
                }
            }
        }
    }
    
    private void periodicStatusReport() {
        System.out.println("[DEBUG] Status reporter thread started");
        System.out.flush();

        while (running.get()) {
            try {
                System.out.println("[DEBUG] Status reporter sleeping for 30 seconds...");
                System.out.flush();
                Thread.sleep(30000); // Report every 30 seconds

                System.out.println("[DEBUG] Status reporter woke up, querying database...");
                System.out.flush();

                try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                    // Get current row count
                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM LOAD_TEST_ORDERS");
                    int rowCount = 0;
                    if (rs.next()) {
                        rowCount = rs.getInt(1);
                    }
                    rs.close();

                    // Try to get session info, but don't fail if we can't
                    int activeSessions = -1;
                    int blockedSessions = -1;

                    try {
                        rs = stmt.executeQuery(
                            "SELECT COUNT(*) FROM v$session WHERE username IS NOT NULL AND status = 'ACTIVE'");
                        if (rs.next()) {
                            activeSessions = rs.getInt(1);
                        }
                        rs.close();

                        rs = stmt.executeQuery(
                            "SELECT COUNT(*) FROM v$session WHERE blocking_session IS NOT NULL");
                        if (rs.next()) {
                            blockedSessions = rs.getInt(1);
                        }
                        rs.close();
                    } catch (SQLException se) {
                        // User might not have access to v$session
                    }

                    if (activeSessions >= 0 && blockedSessions >= 0) {
                        System.out.println(String.format(
                            "[STATUS] Rows: %,d | Active Sessions: %d | Blocked Sessions: %d",
                            rowCount, activeSessions, blockedSessions));
                    } else {
                        System.out.println(String.format("[STATUS] Rows in LOAD_TEST_ORDERS: %,d", rowCount));
                    }
                    System.out.flush(); // Force output

                } catch (SQLException e) {
                    System.err.println("Status report error: " + e.getMessage());
                }

            } catch (InterruptedException e) {
                if (running.get()) {
                    System.err.println("Status reporter interrupted: " + e.getMessage());
                }
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void continuousTableCleanup() {
        System.out.println("[DEBUG] Cleanup thread started");
        System.out.flush();

        while (running.get()) {
            try {
                System.out.println("[DEBUG] Cleanup sleeping for 10 seconds...");
                System.out.flush();
                Thread.sleep(10000); // Run every 10 seconds

                System.out.println("[DEBUG] Cleanup woke up, checking for old rows...");
                System.out.flush();

                try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                    conn.setAutoCommit(false);

                    // Keep only the most recent 200,000 rows to maintain steady state
                    // Delete older rows to prevent unbounded growth
                    int deletedRows = stmt.executeUpdate(
                        "DELETE FROM LOAD_TEST_ORDERS " +
                        "WHERE order_id < (" +
                        "  SELECT MIN(order_id) FROM (" +
                        "    SELECT order_id FROM LOAD_TEST_ORDERS " +
                        "    ORDER BY order_id DESC " +
                        "    FETCH FIRST 200000 ROWS ONLY" +
                        "  )" +
                        ")");

                    conn.commit();

                    if (deletedRows > 0) {
                        System.out.println("Cleanup: Deleted " + deletedRows + " old rows from LOAD_TEST_ORDERS");
                        System.out.flush();
                    }

                } catch (SQLException e) {
                    System.err.println("Table cleanup error: " + e.getMessage());
                }

            } catch (InterruptedException e) {
                if (running.get()) {
                    System.err.println("Cleanup task interrupted: " + e.getMessage());
                }
                Thread.currentThread().interrupt();
                break;
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
