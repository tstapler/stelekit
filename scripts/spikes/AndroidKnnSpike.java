import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Spike 9.1 on ART + Android's SQLite (run via app_process, see ADR-008 addendum).
 * Same workload as KnnScanSpike.java: chunked 500-row BLOB scan with running top-k heap,
 * and the page-centroid pre-filter.
 */
public class AndroidKnnSpike {
    static final int CHUNK = 500, K = 20;

    public static void main(String[] a) {
        int blocks = Integer.parseInt(a[0]), dim = Integer.parseInt(a[1]), pages = Integer.parseInt(a[2]);
        String path = a[3];
        new java.io.File(path).delete();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(path, null);
        db.execSQL("CREATE TABLE emb(id INTEGER PRIMARY KEY, page INTEGER, v BLOB)");
        db.execSQL("CREATE TABLE page_emb(page INTEGER PRIMARY KEY, v BLOB)");
        db.execSQL("CREATE INDEX emb_page ON emb(page)");
        Random rnd = new Random(42);
        float[][] centroid = new float[pages][dim];
        db.beginTransaction();
        SQLiteStatement ins = db.compileStatement("INSERT INTO emb VALUES(?,?,?)");
        for (int i = 0; i < blocks; i++) {
            float[] v = unit(rnd, dim);
            ins.bindLong(1, i); ins.bindLong(2, i % pages); ins.bindBlob(3, toBytes(v));
            ins.executeInsert();
            for (int j = 0; j < dim; j++) centroid[i % pages][j] += v[j];
        }
        SQLiteStatement pi = db.compileStatement("INSERT INTO page_emb VALUES(?,?)");
        for (int p = 0; p < pages; p++) { norm(centroid[p]); pi.bindLong(1, p); pi.bindBlob(2, toBytes(centroid[p])); pi.executeInsert(); }
        db.setTransactionSuccessful();
        db.endTransaction();
        System.out.println("blocks=" + blocks + " dim=" + dim + " pages=" + pages + " maxMemory=" + (Runtime.getRuntime().maxMemory() >> 20) + "MB");
        float[] q = unit(rnd, dim);
        List<double[]> times = new ArrayList<>();
        for (int run = 0; run < 15; run++) {
            long t0 = System.nanoTime(); scanFull(db, q, dim); long t1 = System.nanoTime();
            scanPrefilter(db, q, dim, 50); long t2 = System.nanoTime();
            if (run >= 3) times.add(new double[]{(t1 - t0) / 1e6, (t2 - t1) / 1e6});
        }
        String[] names = {"full-float", "prefilter(50 pages)"};
        for (int k = 0; k < 2; k++) {
            final int kk = k;
            double[] s = times.stream().mapToDouble(x -> x[kk]).sorted().toArray();
            System.out.println(names[k] + " min=" + (long) s[0] + "ms median=" + (long) s[s.length / 2] + "ms max=" + (long) s[s.length - 1] + "ms (n=" + s.length + ")");
        }
        db.close();
    }

    static float[] unit(Random r, int d) { float[] v = new float[d]; for (int i = 0; i < d; i++) v[i] = (float) r.nextGaussian(); norm(v); return v; }
    static void norm(float[] v) { double s = 0; for (float x : v) s += x * x; s = Math.sqrt(s); if (s > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) s; }
    static byte[] toBytes(float[] v) { ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN); for (float f : v) b.putFloat(f); return b.array(); }
    static void offer(PriorityQueue<double[]> h, double s, long id) {
        if (h.size() < K) h.add(new double[]{s, id}); else if (s > h.peek()[0]) { h.poll(); h.add(new double[]{s, id}); }
    }
    static PriorityQueue<double[]> heap() { return new PriorityQueue<>(K + 1, Comparator.comparingDouble(x -> x[0])); }

    static double dot(byte[] b, float[] q, float[] tmp) {
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(tmp);
        double d = 0; for (int i = 0; i < q.length; i++) d += q[i] * tmp[i];
        return d;
    }

    static PriorityQueue<double[]> scanFull(SQLiteDatabase db, float[] q, int dim) {
        PriorityQueue<double[]> h = heap(); float[] tmp = new float[dim]; long last = -1;
        while (true) {
            int n = 0;
            try (Cursor c = db.rawQuery("SELECT id, v FROM emb WHERE id > ? ORDER BY id LIMIT " + CHUNK, new String[]{Long.toString(last)})) {
                while (c.moveToNext()) { n++; last = c.getLong(0); offer(h, dot(c.getBlob(1), q, tmp), last); }
            }
            if (n < CHUNK) break;
        }
        return h;
    }

    static PriorityQueue<double[]> scanPrefilter(SQLiteDatabase db, float[] q, int dim, int topPages) {
        PriorityQueue<double[]> ph = new PriorityQueue<>(topPages + 1, Comparator.comparingDouble(x -> x[0]));
        float[] tmp = new float[dim];
        try (Cursor c = db.rawQuery("SELECT page, v FROM page_emb", null)) {
            while (c.moveToNext()) {
                double d = dot(c.getBlob(1), q, tmp);
                if (ph.size() < topPages) ph.add(new double[]{d, c.getInt(0)}); else if (d > ph.peek()[0]) { ph.poll(); ph.add(new double[]{d, c.getInt(0)}); }
            }
        }
        PriorityQueue<double[]> h = heap();
        for (double[] p : ph) {
            try (Cursor c = db.rawQuery("SELECT id, v FROM emb WHERE page = ?", new String[]{Long.toString((long) p[1])})) {
                while (c.moveToNext()) offer(h, dot(c.getBlob(1), q, tmp), c.getLong(0));
            }
        }
        return h;
    }
}
