import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CDC load generator for the OpenDRS demo task (e.g. DRS-1000).
 *
 *   prepare  create sbtest1..sbtestN (col1 int PK auto_increment, col2 varchar(32))
 *            and fill each with n rows
 *   run      fire concurrent single-row UPDATEs (PK-addressed, so transactions
 *            hold at most one lock and cannot deadlock) and print TPS per second
 *
 * Single-row-per-transaction is deliberate: it is the strongest deadlock
 * guarantee (a cycle needs two locks), and single-row PK updates still exercise
 * the exact path CDC replays (binlog row event -> sink apply).
 */
public final class CdcLoadTool {

    private static final int BATCH = 500;

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            usage();
            return;
        }
        String command = args[0];
        Cli cli = Cli.parse(args, 1);
        switch (command) {
            case "prepare" -> prepare(cli);
            case "run" -> run(cli);
            case "clean" -> clean(cli);
            default -> {
                System.err.println("unknown command: " + command);
                usage();
                System.exit(2);
            }
        }
    }

    // ---------------------------------------------------------------- prepare

    private static void prepare(Cli cli) throws Exception {
        String url = "jdbc:mysql://" + cli.host
                + "?rewriteBatchedStatements=true&sslMode=DISABLED&characterEncoding=utf8"
                + "&allowPublicKeyRetrieval=true";
        Random random = new Random();
        try (Connection conn = DriverManager.getConnection(url, cli.user, cli.password);
                Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + cli.db + "`");
            st.execute("USE `" + cli.db + "`");
            if (cli.autoIncrement > 1 || cli.autoIncrementOffset > 1) {
                // Session-scoped on purpose: the generated PK pattern belongs to this fill run.
                // Classic active-active split: both sides -i 2, one -o 1 (1,3,5...) and the
                // other -o 2 (2,4,6...) — the two sequences can never produce the same value.
                st.execute("SET SESSION auto_increment_increment = " + cli.autoIncrement);
                st.execute("SET SESSION auto_increment_offset = " + cli.autoIncrementOffset);
            }
            for (int t = 1; t <= cli.tableNum; t++) {
                String table = cli.tableName(t);
                st.execute("CREATE TABLE IF NOT EXISTS `" + table + "` ("
                        + " col1 INT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + " col2 VARCHAR(32) NOT NULL) ENGINE=InnoDB");
                st.execute("TRUNCATE TABLE `" + table + "`");
                long start = System.nanoTime();
                long inserted = 0;
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO `" + table
                        + "` (col2) VALUES (?)")) {
                    while (inserted < cli.rows) {
                        int batch = (int) Math.min(BATCH, cli.rows - inserted);
                        for (int i = 0; i < batch; i++) {
                            ps.setString(1, randVarchar(random, 32));
                            ps.addBatch();
                        }
                        ps.executeBatch();
                        inserted += batch;
                    }
                }
                double seconds = (System.nanoTime() - start) / 1e9;
                System.out.printf(Locale.ROOT, "[prepare] %s.%s: %d rows in %.1fs (%.0f rows/s)%n",
                        cli.db, table, inserted, seconds, inserted / seconds);
            }
            System.out.printf(Locale.ROOT, "[prepare] done: %d tables x %d rows in db %s%n",
                    cli.tableNum, cli.rows, cli.db);
        }
    }

    // ------------------------------------------------------------------ clean

    private static void clean(Cli cli) throws Exception {
        String url = "jdbc:mysql://" + cli.host + "/" + cli.db
                + "?sslMode=DISABLED&characterEncoding=utf8&allowPublicKeyRetrieval=true";
        try (Connection conn = DriverManager.getConnection(url, cli.user, cli.password);
                Statement st = conn.createStatement()) {
            for (int t = 1; t <= cli.tableNum; t++) {
                String table = cli.tableName(t);
                st.execute("DROP TABLE IF EXISTS `" + table + "`");
                System.out.printf(Locale.ROOT, "[clean] dropped %s.%s%n", cli.db, table);
            }
            System.out.printf(Locale.ROOT, "[clean] done: %d table(s) removed from db %s%n",
                    cli.tableNum, cli.db);
        }
    }

    // ------------------------------------------------------------------- run

    private static void run(Cli cli) throws Exception {
        String url = "jdbc:mysql://" + cli.host + "/" + cli.db
                + "?rewriteBatchedStatements=true&sslMode=DISABLED&characterEncoding=utf8"
                + "&allowPublicKeyRetrieval=true";
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong done = new AtomicLong();
        AtomicLong retries = new AtomicLong();
        AtomicLong failures = new AtomicLong();

        try (Connection probe = DriverManager.getConnection(url, cli.user, cli.password);
                Statement st = probe.createStatement()) {
            long[] maxIds = new long[cli.tableNum];
            long minMax = Long.MAX_VALUE;
            for (int t = 1; t <= cli.tableNum; t++) {
                String table = cli.tableName(t);
                try (ResultSet rs = st.executeQuery("SELECT MAX(col1) FROM `" + table + "`")) {
                    rs.next();
                    maxIds[t - 1] = rs.getLong(1);
                }
                if (maxIds[t - 1] <= 0) {
                    System.err.println("table " + cli.db + "." + table
                            + " is empty - run `prepare` first (with the same --table-num)");
                    System.exit(1);
                    return;
                }
                minMax = Math.min(minMax, maxIds[t - 1]);
            }

            System.out.printf(Locale.ROOT,
                    "[run] %d threads updating %d tables (%s1..%s%d, min max(col1)=%d), %d row(s)/tx, %s%n",
                    cli.threads, cli.tableNum, cli.tablePrefix, cli.tablePrefix, cli.tableNum,
                    minMax, cli.rowsPerTx, cli.seconds > 0 ? cli.seconds + "s" : "until Ctrl+C");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> stop.set(true)));

            for (int t = 0; t < cli.threads; t++) {
                Thread worker = new Thread(() -> worker(url, cli, maxIds, stop, done, retries, failures),
                        "load-" + t);
                worker.setDaemon(true);
                worker.start();
            }

            long windowStart = System.nanoTime();
            long windowDone = 0;
            long wallStart = System.nanoTime();
            while (!stop.get()) {
                Thread.sleep(1000);
                long now = System.nanoTime();
                long total = done.get();
                long tps = Math.round((total - windowDone) / ((now - windowStart) / 1e9));
                windowStart = now;
                windowDone = total;
                System.out.printf(Locale.ROOT, "[tps] %,d rows/s | total %,d rows | dead-lock retries %,d | failures %,d%n",
                        tps, total, retries.get(), failures.get());
                if (cli.seconds > 0 && (now - wallStart) / 1e9 >= cli.seconds) {
                    stop.set(true);
                }
            }
            double wall = (System.nanoTime() - wallStart) / 1e9;
            long total = done.get();
            System.out.printf(Locale.ROOT,
                    "[run] finished: %,d row updates in %.1fs, avg %,.0f rows/s, retries %,d, failures %,d%n",
                    total, wall, total / wall, retries.get(), failures.get());
        }
    }

    /**
     * One UPDATE per transaction, addressed by primary key, on a table chosen
     * uniformly at random. A transaction that locks at most one row cannot be
     * part of a lock cycle, so this workload is deadlock-free by construction;
     * the retry counter should stay at 0.
     */
    private static void worker(String url, Cli cli, long[] maxIds, AtomicBoolean stop,
            AtomicLong done, AtomicLong retries, AtomicLong failures) {
        Random random = new Random();
        String[] sql = new String[maxIds.length];
        for (int t = 1; t <= maxIds.length; t++) {
            sql[t - 1] = "UPDATE `" + cli.tableName(t) + "` SET col2 = ? WHERE col1 = ?";
        }
        while (!stop.get()) {
            try (Connection conn = DriverManager.getConnection(url, cli.user, cli.password)) {
                conn.setAutoCommit(false);
                PreparedStatement[] pss = new PreparedStatement[sql.length];
                try {
                    for (int t = 0; t < sql.length; t++) {
                        pss[t] = conn.prepareStatement(sql[t]);
                    }
                    while (!stop.get()) {
                        // -k rows per transaction: k random single-row UPDATEs in one tx.
                        // k stays small (default 1, deadlock-free by construction); larger k
                        // trades a little lock-set overlap for a visible tx-vs-row rate split.
                        for (int q = 0; q < cli.rowsPerTx; q++) {
                            int table = random.nextInt(sql.length);
                            long id = 1 + Math.floorMod(random.nextLong(), maxIds[table]);
                            PreparedStatement ps = pss[table];
                            ps.setString(1, randVarchar(random, 32));
                            ps.setLong(2, id);
                            ps.executeUpdate();
                            done.incrementAndGet();
                        }
                        conn.commit();
                    }
                } finally {
                    for (PreparedStatement ps : pss) {
                        if (ps != null) {
                            ps.close();
                        }
                    }
                }
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("Deadlock") || msg.contains("Lock wait timeout")) {
                    retries.incrementAndGet();
                } else if (!stop.get()) {
                    failures.incrementAndGet();
                    System.err.println("[run] worker error: " + msg);
                }
                sleepQuietly(5);
            }
        }
    }

    private static String randVarchar(Random random, int maxLen) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        int len = 8 + random.nextInt(maxLen - 7);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------- cli

    private static final class Cli {
        String host = "127.0.0.1:3306";
        String user = "root";
        String password = "";
        String db = "cdc_load";
        String tablePrefix = "sbtest";
        int tableNum = 1;
        long rows = 10_000;
        int threads = 4;
        int rowsPerTx = 1;
        int seconds = 0;
        int autoIncrement = 1;
        int autoIncrementOffset = 1;

        String tableName(int t) {
            return tablePrefix + t;
        }

        static Cli parse(String[] args, int from) {
            Cli cli = new Cli();
            for (int i = from; i < args.length; i++) {
                String key = args[i];
                switch (key) {
                    case "-h" -> cli.host = value(args, ++i, key);
                    case "-u" -> cli.user = value(args, ++i, key);
                    case "-p" -> cli.password = value(args, ++i, key);
                    case "-d" -> cli.db = value(args, ++i, key);
                    case "-t" -> cli.tablePrefix = value(args, ++i, key);
                    case "-n" -> cli.rows = Long.parseLong(value(args, ++i, key));
                    case "--table-num" -> cli.tableNum = Integer.parseInt(value(args, ++i, key));
                    case "-i" -> cli.autoIncrement = Integer.parseInt(value(args, ++i, key));
                    case "-o" -> cli.autoIncrementOffset = Integer.parseInt(value(args, ++i, key));
                    case "-T" -> cli.threads = Integer.parseInt(value(args, ++i, key));
                    case "-k" -> cli.rowsPerTx = Integer.parseInt(value(args, ++i, key));
                    case "-s" -> cli.seconds = Integer.parseInt(value(args, ++i, key));
                    default -> {
                        System.err.println("unknown option: " + key);
                        usage();
                        System.exit(2);
                    }
                }
            }
            if (cli.tableNum < 1) {
                System.err.println("--table-num must be >= 1");
                System.exit(2);
            }
            if (cli.autoIncrement < 1 || cli.autoIncrement > 65535) {
                System.err.println("-i (auto_increment_increment) must be in 1..65535");
                System.exit(2);
            }
            if (cli.autoIncrementOffset < 1 || cli.autoIncrementOffset > cli.autoIncrement) {
                System.err.println("-o (auto_increment_offset) must be in 1..-i");
                System.exit(2);
            }
            if (cli.rowsPerTx < 1 || cli.rowsPerTx > 1000) {
                System.err.println("-k (rows per transaction) must be in 1..1000");
                System.exit(2);
            }
            return cli;
        }

        private static String value(String[] args, int i, String key) {
            if (i >= args.length) {
                System.err.println("option " + key + " needs a value");
                System.exit(2);
            }
            return args[i];
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void usage() {
        System.out.println("""
                CDC load generator for OpenDRS

                java -jar cdc-load-tool.jar prepare -h 127.0.0.1:3306 -u root -p 123456 \\
                        -d cdc_load --table-num 10 -n 10000
                    Create cdc_load.sbtest1..sbtest10
                    (col1 INT PRIMARY KEY AUTO_INCREMENT, col2 VARCHAR(32))
                    and fill EACH table with n rows (truncates first).
                    -t sets the table prefix (default sbtest).
                    -i sets the PK auto-increment step (default 1), -o the starting offset
                    (default 1, must be 1..-i). Session-scoped, so the generated col1 values
                    are offset, offset+i, offset+2i...
                    Classic bidirectional-sync split: side A -i 2 -o 1 (1,3,5...),
                    side B -i 2 -o 2 (2,4,6...) - the two sequences never collide.
                    Note: -i/-o only shape the data THIS tool inserts; for ongoing writes
                    each MySQL server needs auto_increment_increment/offset in its own config.

                java -jar cdc-load-tool.jar run -h 127.0.0.1:3306 -u root -p 123456 \\
                        -d cdc_load --table-num 10 -T 8 -k 4 -s 60
                    Concurrent single-row UPDATE workload across all tables.
                    -T threads (default 4), -s seconds (default 0 = until Ctrl+C),
                    -k rows per transaction (default 1; k>1 makes each transaction
                    carry k single-row UPDATEs, so replay QPS = k x replay TPS on the
                    monitor — keep k small, the deadlock-free guarantee weakens as
                    each transaction holds k locks at once).
                    Prints rows/s every second.

                java -jar cdc-load-tool.jar clean -h 127.0.0.1:3306 -u root -p 123456 \\
                        -d cdc_load --table-num 10
                    Drop cdc_load.sbtest1..sbtest10 (the tables `prepare` created).
                    The database itself is kept; -t sets the table prefix (default sbtest).

                Deadlock-free by construction: every transaction updates exactly one
                row addressed by primary key, so no transaction ever holds two locks.
                """);
    }

    private CdcLoadTool() {
    }
}
