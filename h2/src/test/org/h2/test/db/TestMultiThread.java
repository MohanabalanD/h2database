/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.db;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.h2.api.ErrorCode;
import org.h2.jdbc.JdbcSQLException;
import org.h2.test.TestAll;
import org.h2.test.TestBase;
import org.h2.util.SmallLRUCache;
import org.h2.util.SynchronizedVerifier;
import org.h2.util.Task;

/**
 * Multi-threaded tests.
 */
public class TestMultiThread extends TestBase implements Runnable {

    private boolean stop;
    private TestMultiThread parent;
    private Random random;
    private Connection threadConn;
    private Statement threadStat;

    public TestMultiThread() {
        // nothing to do
    }

    private TestMultiThread(TestAll config, TestMultiThread parent)
            throws SQLException {
        this.config = config;
        this.parent = parent;
        random = new Random();
        threadConn = getConnection();
        threadStat = threadConn.createStatement();
    }

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().test();
    }

    @Override
    public void test() throws Exception {
        testConcurrentSchemaChange();
        testConcurrentLobAdd();
        testConcurrentView();
        testConcurrentAlter();
        testConcurrentAnalyze();
        testConcurrentInsertUpdateSelect();
        testLockModeWithMultiThreaded();
        testViews();
    }

    private void testConcurrentSchemaChange() throws Exception {
        String db = getTestName();
        deleteDb(db);
        final String url = getURL(db + ";MULTI_THREADED=1;LOCK_TIMEOUT=10000", true);
        Connection conn = getConnection(url);
        Task[] tasks = new Task[2];
        for (int i = 0; i < tasks.length; i++) {
            final int x = i;
            Task t = new Task() {
                @Override
                public void call() throws Exception {
                    try (Connection c2 = getConnection(url)) {
                        Statement stat = c2.createStatement();
                        for (int i = 0; !stop; i++) {
                            stat.execute("create table test" + x + "_" + i);
                            c2.getMetaData().getTables(null, null, null, null);
                            stat.execute("drop table test" + x + "_" + i);
                        }
                    }
                }
            };
            tasks[i] = t;
            t.execute();
        }
        Thread.sleep(1000);
        for (Task t : tasks) {
            t.get();
        }
        conn.close();
    }

    private void testConcurrentLobAdd() throws Exception {
        String db = getTestName();
        deleteDb(db);
        final String url = getURL(db + ";MULTI_THREADED=1", true);
        Connection conn = getConnection(url);
        Statement stat = conn.createStatement();
        stat.execute("create table test(id identity, data clob)");
        Task[] tasks = new Task[2];
        for (int i = 0; i < tasks.length; i++) {
            Task t = new Task() {
                @Override
                public void call() throws Exception {
                    try (Connection c2 = getConnection(url)) {
                        PreparedStatement p2 = c2
                                .prepareStatement("insert into test(data) values(?)");
                        while (!stop) {
                            p2.setCharacterStream(1, new StringReader(new String(
                                    new char[10 * 1024])));
                            p2.execute();
                        }
                    }
                }
            };
            tasks[i] = t;
            t.execute();
        }
        Thread.sleep(500);
        for (Task t : tasks) {
            t.get();
        }
        conn.close();
    }

    private void testConcurrentView() throws Exception {
        if (config.mvcc || config.mvStore) {
            return;
        }
        String db = getTestName();
        deleteDb(db);
        final String url = getURL(db + ";MULTI_THREADED=1", true);
        final Random r = new Random();
        Connection conn = getConnection(url);
        Statement stat = conn.createStatement();
        StringBuilder buff = new StringBuilder();
        buff.append("create table test(id int");
        final int len = 3;
        for (int i = 0; i < len; i++) {
            buff.append(", x" + i + " int");
        }
        buff.append(")");
        stat.execute(buff.toString());
        stat.execute("create view test_view as select * from test");
        stat.execute("insert into test(id) select x from system_range(1, 2)");
        Task t = new Task() {
            @Override
            public void call() throws Exception {
                Connection c2 = getConnection(url);
                while (!stop) {
                    c2.prepareStatement("select * from test_view where x" +
                            r.nextInt(len) + "=1");
                }
                c2.close();
            }
        };
        t.execute();
        SynchronizedVerifier.setDetect(SmallLRUCache.class, true);
        for (int i = 0; i < 1000; i++) {
            conn.prepareStatement("select * from test_view where x" +
                    r.nextInt(len) + "=1");
        }
        t.get();
        SynchronizedVerifier.setDetect(SmallLRUCache.class, false);
        conn.close();
    }

    private void testConcurrentAlter() throws Exception {
        deleteDb(getTestName());
        final Connection conn = getConnection(getTestName());
        Statement stat = conn.createStatement();
        Task t = new Task() {
            @Override
            public void call() throws Exception {
                while (!stop) {
                    conn.prepareStatement("select * from test");
                }
            }
        };
        stat.execute("create table test(id int)");
        t.execute();
        for (int i = 0; i < 200; i++) {
            stat.execute("alter table test add column x int");
            stat.execute("alter table test drop column x");
        }
        t.get();
        conn.close();
    }

    private void testConcurrentAnalyze() throws Exception {
        if (config.mvcc) {
            return;
        }
        deleteDb(getTestName());
        final String url = getURL("concurrentAnalyze;MULTI_THREADED=1", true);
        Connection conn = getConnection(url);
        Statement stat = conn.createStatement();
        stat.execute("create table test(id bigint primary key) " +
                "as select x from system_range(1, 1000)");
        Task t = new Task() {
            @Override
            public void call() throws SQLException {
                Connection conn2;
                conn2 = getConnection(url);
                for (int i = 0; i < 1000; i++) {
                    conn2.createStatement().execute("analyze");
                }
                conn2.close();
            }
        };
        t.execute();
        Thread.yield();
        for (int i = 0; i < 1000; i++) {
            conn.createStatement().execute("analyze");
        }
        t.get();
        stat.execute("drop table test");
        conn.close();
    }

    private void testConcurrentInsertUpdateSelect() throws Exception {
        threadConn = getConnection();
        threadStat = threadConn.createStatement();
        threadStat.execute("CREATE TABLE TEST(ID IDENTITY, NAME VARCHAR)");
        int len = getSize(10, 200);
        Thread[] threads = new Thread[len];
        for (int i = 0; i < len; i++) {
            threads[i] = new Thread(new TestMultiThread(config, this));
        }
        for (int i = 0; i < len; i++) {
            threads[i].start();
        }
        int sleep = getSize(400, 10000);
        Thread.sleep(sleep);
        this.stop = true;
        for (int i = 0; i < len; i++) {
            threads[i].join();
        }
        ResultSet rs = threadStat.executeQuery("SELECT COUNT(*) FROM TEST");
        rs.next();
        trace("max id=" + rs.getInt(1));
        threadConn.close();
    }

    private Connection getConnection() throws SQLException {
        return getConnection("jdbc:h2:mem:" + getTestName());
    }

    @Override
    public void run() {
        try {
            while (!parent.stop) {
                threadStat.execute("SELECT COUNT(*) FROM TEST");
                threadStat.execute("INSERT INTO TEST VALUES(NULL, 'Hi')");
                PreparedStatement prep = threadConn.prepareStatement(
                        "UPDATE TEST SET NAME='Hello' WHERE ID=?");
                prep.setInt(1, random.nextInt(10000));
                prep.execute();
                prep = threadConn.prepareStatement("SELECT * FROM TEST WHERE ID=?");
                prep.setInt(1, random.nextInt(10000));
                ResultSet rs = prep.executeQuery();
                while (rs.next()) {
                    rs.getString("NAME");
                }
            }
            threadConn.close();
        } catch (Exception e) {
            logError("multi", e);
        }
    }

    private void testLockModeWithMultiThreaded() throws Exception {
        // currently the combination of LOCK_MODE=0 and MULTI_THREADED
        // is not supported
        deleteDb("lockMode");
        final String url = getURL("lockMode;MULTI_THREADED=1", true);
        Connection conn = getConnection(url);
        DatabaseMetaData meta = conn.getMetaData();
        assertFalse(meta.supportsTransactionIsolationLevel(
                Connection.TRANSACTION_READ_UNCOMMITTED));
        conn.close();
        deleteDb("lockMode");
    }

    private void testViews() throws Exception {
        // currently the combination of LOCK_MODE=0 and MULTI_THREADED
        // is not supported
        deleteDb("lockMode");
        final String url = getURL("lockMode;MULTI_THREADED=1", true);

        // create some common tables and views
        final Connection conn = getConnection(url);
        final Statement stat = conn.createStatement();
        stat.execute(
                "CREATE TABLE INVOICE(INVOICE_ID INT PRIMARY KEY, AMOUNT DECIMAL)");
        stat.execute("CREATE VIEW INVOICE_VIEW as SELECT * FROM INVOICE");

        stat.execute(
                "CREATE TABLE INVOICE_DETAIL(DETAIL_ID INT PRIMARY KEY, " +
                "INVOICE_ID INT, DESCRIPTION VARCHAR)");
        stat.execute(
                "CREATE VIEW INVOICE_DETAIL_VIEW as SELECT * FROM INVOICE_DETAIL");

        stat.close();
        conn.close();
        
        // Without this we fail when testing in "mvstore net memory" mode with "Table INVOICE_VIEW not found"
        // which is not the point of this test, so just workaround
        Thread.sleep(100);
        
        // create views that reference the common views in different threads
        final ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            final ArrayList<Future<Void>> jobs = new ArrayList<Future<Void>>();
            for (int i = 0; i < 1000; i++) {
                final int j = i;
                jobs.add(executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        final Connection conn2 = getConnection(url);
                        Statement stat2 = conn2.createStatement();
    
                        stat2.execute("CREATE VIEW INVOICE_VIEW" + j
                                + " as SELECT * FROM INVOICE_VIEW");
    
                        // the following query intermittently results in a
                        // NullPointerException
                        stat2.execute("CREATE VIEW INVOICE_DETAIL_VIEW" + j
                                + " as SELECT DTL.* FROM INVOICE_VIEW" + j
                                + " INV JOIN INVOICE_DETAIL_VIEW DTL "
                                + "ON INV.INVOICE_ID = DTL.INVOICE_ID"
                                + " WHERE DESCRIPTION='TEST'");
    
                        ResultSet rs = stat2
                                .executeQuery("SELECT * FROM INVOICE_VIEW" + j);
                        rs.next();
                        rs.close();
    
                        rs = stat2.executeQuery(
                                "SELECT * FROM INVOICE_DETAIL_VIEW" + j);
                        rs.next();
                        rs.close();
    
                        stat.close();
                        conn.close();
                        return null;
                    }
                }));
            }
            // check for exceptions
            for (Future<Void> job : jobs) {
                try {
                    job.get();
                } catch (ExecutionException ex) {
                    // ignore timeout exceptions, happens periodically when the machine is really
                    // busy and it's not the thing we are trying to test
                    if (!(ex.getCause() instanceof JdbcSQLException)
                            || ((JdbcSQLException) ex.getCause())
                                    .getErrorCode() != ErrorCode.LOCK_TIMEOUT_1) {
                        throw ex;
                    }
                }
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(20, TimeUnit.SECONDS);
        }

        deleteDb("lockMode");
    }
}
