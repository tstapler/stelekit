import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Spike 9.1: full-scan kNN latency over persisted embeddings (U15 / ADR-008 addendum).
 * Run: java -Xmx512m -cp sqlite-jdbc.jar:slf4j-api.jar scripts/spikes/KnnScanSpike.java BLOCKS DIM PAGES
 * Measures (1) chunked SQLite BLOB scan (500 rows/chunk, running top-k heap), (2) int8-quantised
 * variant, (3) page-centroid pre-filter followed by a block scan of the top pages only.
 */
public class KnnScanSpike {
    static final int CHUNK = 500, K = 20;

    public static void main(String[] a) throws Exception {
        int blocks = Integer.parseInt(a[0]), dim = Integer.parseInt(a[1]), pages = Integer.parseInt(a[2]);
        Path db = Files.createTempFile("knn-spike", ".db");
        Random rnd = new Random(42);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE emb(id INTEGER PRIMARY KEY, page INTEGER, v BLOB, q BLOB)");
                st.execute("CREATE TABLE page_emb(page INTEGER PRIMARY KEY, v BLOB)");
                st.execute("CREATE INDEX emb_page ON emb(page)");
            }
            c.setAutoCommit(false);
            PreparedStatement ins = c.prepareStatement("INSERT INTO emb VALUES(?,?,?,?)");
            float[][] centroid = new float[pages][dim];
            for (int i = 0; i < blocks; i++) {
                int page = i % pages;
                float[] v = unit(rnd, dim);
                ins.setInt(1, i); ins.setInt(2, page); ins.setBytes(3, toBytes(v)); ins.setBytes(4, quant(v));
                ins.addBatch();
                for (int j = 0; j < dim; j++) centroid[page][j] += v[j];
                if (i % 5000 == 0) ins.executeBatch();
            }
            ins.executeBatch();
            PreparedStatement pi = c.prepareStatement("INSERT INTO page_emb VALUES(?,?)");
            for (int p = 0; p < pages; p++) { norm(centroid[p]); pi.setInt(1, p); pi.setBytes(2, toBytes(centroid[p])); pi.addBatch(); }
            pi.executeBatch();
            c.commit();
        }
        System.out.printf("db size %.1f MB, blocks=%d dim=%d pages=%d Xmx=%dMB cpus=%d%n",
            Files.size(db) / 1e6, blocks, dim, pages, Runtime.getRuntime().maxMemory() >> 20, Runtime.getRuntime().availableProcessors());
        float[] q = unit(rnd, dim);
        List<double[]> times = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            for (int run = 0; run < 25; run++) {
                long t0 = System.nanoTime(); scanFull(c, q, dim, "v"); long t1 = System.nanoTime();
                scanFull(c, q, dim, "q"); long t2 = System.nanoTime();
                scanPrefilter(c, q, dim, 50); long t3 = System.nanoTime();
                if (run >= 5) times.add(new double[]{(t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6});
            }
        }
        String[] names = {"full-float", "full-int8", "prefilter(50 pages)"};
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            double[] s = times.stream().mapToDouble(x -> x[kk]).sorted().toArray();
            System.out.printf("%-20s min=%.0fms median=%.0fms p95=%.0fms max=%.0fms%n", names[k], s[0], s[s.length / 2], s[(int) (s.length * 0.95) - 1], s[s.length - 1]);
        }
        Files.deleteIfExists(db);
    }

    static float[] unit(Random r, int d) { float[] v = new float[d]; for (int i = 0; i < d; i++) v[i] = (float) r.nextGaussian(); norm(v); return v; }
    static void norm(float[] v) { double s = 0; for (float x : v) s += x * x; s = Math.sqrt(s); if (s > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) s; }
    static byte[] toBytes(float[] v) { ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN); for (float f : v) b.putFloat(f); return b.array(); }
    static byte[] quant(float[] v) { byte[] o = new byte[v.length]; for (int i = 0; i < v.length; i++) o[i] = (byte) Math.max(-127, Math.min(127, Math.round(v[i] * 127))); return o; }

    static PriorityQueue<double[]> heap() { return new PriorityQueue<>(K + 1, Comparator.comparingDouble(x -> x[0])); }
    static void offer(PriorityQueue<double[]> h, double s, long id) { if (h.size() < K) h.add(new double[]{s, id}); else if (s > h.peek()[0]) { h.poll(); h.add(new double[]{s, id}); } }

    static PriorityQueue<double[]> scanFull(Connection c, float[] q, int dim, String col) throws SQLException {
        PriorityQueue<double[]> h = heap();
        boolean f = col.equals("v");
        float[] tmp = new float[dim];
        long last = -1;
        PreparedStatement ps = c.prepareStatement("SELECT id, " + col + " FROM emb WHERE id > ? ORDER BY id LIMIT " + CHUNK);
        while (true) {
            ps.setLong(1, last); int n = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    n++; last = rs.getLong(1); byte[] b = rs.getBytes(2); double dot = 0;
                    if (f) { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(tmp); for (int i = 0; i < dim; i++) dot += q[i] * tmp[i]; }
                    else { for (int i = 0; i < dim; i++) dot += q[i] * b[i]; dot /= 127; }
                    offer(h, dot, last);
                }
            }
            if (n < CHUNK) break;
        }
        return h;
    }

    static PriorityQueue<double[]> scanPrefilter(Connection c, float[] q, int dim, int topPages) throws SQLException {
        PriorityQueue<double[]> ph = new PriorityQueue<>(topPages + 1, Comparator.comparingDouble(x -> x[0]));
        float[] tmp = new float[dim];
        try (ResultSet rs = c.createStatement().executeQuery("SELECT page, v FROM page_emb")) {
            while (rs.next()) {
                ByteBuffer.wrap(rs.getBytes(2)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(tmp);
                double dot = 0; for (int i = 0; i < dim; i++) dot += q[i] * tmp[i];
                if (ph.size() < topPages) ph.add(new double[]{dot, rs.getInt(1)}); else if (dot > ph.peek()[0]) { ph.poll(); ph.add(new double[]{dot, rs.getInt(1)}); }
            }
        }
        PriorityQueue<double[]> h = heap();
        PreparedStatement ps = c.prepareStatement("SELECT id, v FROM emb WHERE page = ?");
        for (double[] p : ph) {
            ps.setInt(1, (int) p[1]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ByteBuffer.wrap(rs.getBytes(2)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(tmp);
                    double dot = 0; for (int i = 0; i < dim; i++) dot += q[i] * tmp[i];
                    offer(h, dot, rs.getLong(1));
                }
            }
        }
        return h;
    }
}
