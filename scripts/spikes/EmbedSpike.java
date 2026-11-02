import ai.onnxruntime.*;
import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.*;

/**
 * Spike 9.1 model half: all-MiniLM-L6-v2 (ONNX) embedding latency/heap, and recall of the
 * page-centroid pre-filter vs full scan on a real corpus (markdown files = pages, paragraphs = blocks).
 * Run: java -Xmx512m -cp onnxruntime.jar scripts/spikes/EmbedSpike.java MODEL.onnx vocab.txt CORPUS_DIR MAX_BLOCKS
 */
public class EmbedSpike {
    static final int MAXLEN = 128, BATCH = 16, K = 20;
    static Map<String, Integer> vocab = new HashMap<>();

    public static void main(String[] a) throws Exception {
        List<String> v = Files.readAllLines(Path.of(a[1]));
        for (int i = 0; i < v.size(); i++) vocab.put(v.get(i), i);
        int maxBlocks = Integer.parseInt(a[3]);
        List<String> texts = new ArrayList<>();
        List<Integer> pageOf = new ArrayList<>();
        int pages = loadCorpus(Path.of(a[2]), maxBlocks, texts, pageOf);
        System.out.println("corpus: pages=" + pages + " blocks=" + texts.size() + " Xmx=" + (Runtime.getRuntime().maxMemory() >> 20) + "MB");
        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             OrtSession.SessionOptions so = new OrtSession.SessionOptions()) {
            so.setIntraOpNumThreads(Integer.getInteger("threads", 4));
            try (OrtSession s = env.createSession(a[0], so)) {
                long[] peak = {0};
                Thread sampler = new Thread(() -> { try { while (true) { Runtime r = Runtime.getRuntime(); peak[0] = Math.max(peak[0], r.totalMemory() - r.freeMemory()); Thread.sleep(20); } } catch (InterruptedException e) { } });
                sampler.setDaemon(true); sampler.start();
                embed(env, s, texts.subList(0, Math.min(64, texts.size())));
                int n = Math.min(1000, texts.size());
                long t0 = System.nanoTime();
                float[][] first = embed(env, s, texts.subList(0, n));
                double ms = (System.nanoTime() - t0) / 1e6;
                System.out.printf("dim=%d embed %d blocks: total=%.0fms per-block=%.2fms peakJavaHeap=%dMB VmHWM=%s%n",
                    first[0].length, n, ms, ms / n, peak[0] >> 20, vmHwm());
                if (a.length > 4) return;
                float[][] all = embed(env, s, texts);
                recall(env, s, texts, pageOf, pages, all);
                ivf(env, s, texts, all);
            }
        }
    }

    static String vmHwm() throws IOException {
        return Files.readAllLines(Path.of("/proc/self/status")).stream().filter(l -> l.startsWith("VmHWM")).findFirst().orElse("n/a").replaceAll("\\s+", " ");
    }

    static int loadCorpus(Path dir, int max, List<String> texts, List<Integer> pageOf) throws IOException {
        List<Path> files;
        try (Stream<Path> st = Files.walk(dir)) { files = st.filter(p -> p.toString().endsWith(".md")).sorted().collect(Collectors.toList()); }
        int page = 0;
        Random r = new Random(7);
        Collections.shuffle(files, r);
        for (Path f : files) {
            int before = texts.size();
            for (String para : Files.readString(f).split("\\n\\s*\\n")) {
                String t = para.replaceAll("[#*`>|\\-]+", " ").replaceAll("\\s+", " ").trim();
                if (t.length() >= 40 && t.length() <= 600) { texts.add(t); pageOf.add(page); }
            }
            if (texts.size() > before) page++;
            if (texts.size() >= max) break;
        }
        return page;
    }

    static float[][] embed(OrtEnvironment env, OrtSession s, List<String> texts) throws OrtException {
        float[][] out = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i += BATCH) {
            List<String> b = texts.subList(i, Math.min(texts.size(), i + BATCH));
            long[][] ids = new long[b.size()][];
            int len = 0;
            for (int j = 0; j < b.size(); j++) { ids[j] = tokenize(b.get(j)); len = Math.max(len, ids[j].length); }
            long[] in = new long[b.size() * len], mask = new long[b.size() * len], type = new long[b.size() * len];
            for (int j = 0; j < b.size(); j++) for (int k = 0; k < ids[j].length; k++) { in[j * len + k] = ids[j][k]; mask[j * len + k] = 1; }
            long[] shape = {b.size(), len};
            Map<String, OnnxTensor> feed = new HashMap<>();
            feed.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(in), shape));
            feed.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape));
            feed.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(type), shape));
            try (OrtSession.Result res = s.run(feed)) {
                float[][][] h = (float[][][]) res.get(0).getValue();
                for (int j = 0; j < b.size(); j++) {
                    int d = h[j][0].length; float[] e = new float[d]; int cnt = 0;
                    for (int k = 0; k < len; k++) if (mask[j * len + k] == 1) { cnt++; for (int x = 0; x < d; x++) e[x] += h[j][k][x]; }
                    norm(e); out[i + j] = e;
                }
            } finally { feed.values().forEach(OnnxTensor::close); }
        }
        return out;
    }

    static long[] tokenize(String text) {
        List<Long> ids = new ArrayList<>(); ids.add((long) vocab.get("[CLS]"));
        String t = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{Mn}", "");
        for (String w : t.split("(?<=\\p{Punct})|(?=\\p{Punct})|\\s+")) {
            if (w.isBlank()) continue;
            int start = 0; List<Long> pieces = new ArrayList<>(); boolean bad = false;
            while (start < w.length()) {
                int end = w.length(); Integer id = null;
                while (end > start) { String sub = (start > 0 ? "##" : "") + w.substring(start, end); id = vocab.get(sub); if (id != null) break; end--; }
                if (id == null) { bad = true; break; }
                pieces.add((long) id); start = end;
            }
            if (bad) ids.add((long) vocab.get("[UNK]")); else ids.addAll(pieces);
            if (ids.size() >= MAXLEN - 1) break;
        }
        ids.add((long) vocab.get("[SEP]"));
        return ids.stream().mapToLong(Long::longValue).toArray();
    }

    static void norm(float[] v) { double s = 0; for (float x : v) s += x * x; s = Math.sqrt(s); if (s > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) s; }
    static double dot(float[] a, float[] b) { double d = 0; for (int i = 0; i < a.length; i++) d += a[i] * b[i]; return d; }

    static void recall(OrtEnvironment env, OrtSession s, List<String> texts, List<Integer> pageOf, int pages, float[][] all) throws OrtException {
        int dim = all[0].length;
        float[][] cen = new float[pages][dim];
        for (int i = 0; i < all.length; i++) for (int x = 0; x < dim; x++) cen[pageOf.get(i)][x] += all[i][x];
        for (float[] c : cen) norm(c);
        Random r = new Random(11);
        List<String> queries = new ArrayList<>();
        for (int q = 0; q < 300; q++) {
            String[] w = texts.get(r.nextInt(texts.size())).split(" ");
            int from = r.nextInt(Math.max(1, w.length - 6));
            queries.add(String.join(" ", Arrays.copyOfRange(w, from, Math.min(w.length, from + 3 + r.nextInt(4)))));
        }
        float[][] qe = embed(env, s, queries);
        int[] topN = {10, 25, 50, 100, 200, 400};
        double[] rec = new double[topN.length];
        int[] needed = new int[qe.length];
        for (int qi = 0; qi < qe.length; qi++) {
            Set<Integer> truth = topK(qe[qi], all, null, K);
            Integer[] order = IntStream.range(0, pages).boxed().sorted(Comparator.comparingDouble(p -> -dot(qe[0 + 0], cen[p]))).toArray(Integer[]::new);
            final float[] q = qe[qi];
            order = IntStream.range(0, pages).boxed().sorted(Comparator.comparingDouble(p -> -dot(q, cen[p]))).toArray(Integer[]::new);
            int[] rank = new int[pages]; for (int i = 0; i < pages; i++) rank[order[i]] = i;
            int maxRank = 0; for (int id : truth) maxRank = Math.max(maxRank, rank[pageOf.get(id)]);
            needed[qi] = maxRank + 1;
            for (int t = 0; t < topN.length; t++) { int n = topN[t], hit = 0; for (int id : truth) if (rank[pageOf.get(id)] < n) hit++; rec[t] += hit / (double) truth.size(); }
        }
        System.out.println("recall@" + K + " of centroid pre-filter vs full scan, " + qe.length + " queries, " + pages + " pages:");
        for (int t = 0; t < topN.length; t++) System.out.printf("  top %d pages (%.1f%% of pages): recall=%.3f%n", topN[t], 100.0 * topN[t] / pages, rec[t] / qe.length);
        Arrays.sort(needed);
        System.out.printf("  pages needed for full recall: p50=%d p90=%d p95=%d max=%d%n", needed[needed.length / 2], needed[(int) (needed.length * 0.9)], needed[(int) (needed.length * 0.95)], needed[needed.length - 1]);
    }

    static Set<Integer> topK(float[] q, float[][] all, Set<Integer> allowed, int k) {
        PriorityQueue<double[]> h = new PriorityQueue<>(Comparator.comparingDouble(x -> x[0]));
        for (int i = 0; i < all.length; i++) { double d = dot(q, all[i]); if (h.size() < k) h.add(new double[]{d, i}); else if (d > h.peek()[0]) { h.poll(); h.add(new double[]{d, i}); } }
        Set<Integer> out = new HashSet<>(); for (double[] x : h) out.add((int) x[1]); return out;
    }

    /** IVF: spherical k-means over blocks (sqrt(N) lists), probe the nprobe nearest lists. */
    static void ivf(OrtEnvironment env, OrtSession s, List<String> texts, float[][] all) throws OrtException {
        int n = all.length, dim = all[0].length, nl = (int) Math.round(Math.sqrt(n));
        Random r = new Random(5);
        float[][] c = new float[nl][];
        for (int i = 0; i < nl; i++) c[i] = all[r.nextInt(n)].clone();
        int[] asg = new int[n];
        for (int it = 0; it < 12; it++) {
            float[][] sum = new float[nl][dim];
            for (int i = 0; i < n; i++) { int b = nearest(c, all[i]); asg[i] = b; for (int x = 0; x < dim; x++) sum[b][x] += all[i][x]; }
            for (int j = 0; j < nl; j++) { norm(sum[j]); if (sum[j][0] != 0 || sum[j][1] != 0) c[j] = sum[j]; }
        }
        List<List<Integer>> lists = new ArrayList<>(); for (int j = 0; j < nl; j++) lists.add(new ArrayList<>());
        for (int i = 0; i < n; i++) lists.get(asg[i]).add(i);
        List<String> queries = new ArrayList<>(); Random q2 = new Random(11);
        for (int q = 0; q < 300; q++) {
            String[] w = texts.get(q2.nextInt(n)).split(" ");
            int from = q2.nextInt(Math.max(1, w.length - 6));
            queries.add(String.join(" ", Arrays.copyOfRange(w, from, Math.min(w.length, from + 3 + q2.nextInt(4)))));
        }
        float[][] qe = embed(env, s, queries);
        System.out.println("IVF lists=" + nl + " over " + n + " blocks:");
        for (int nprobe : new int[]{4, 8, 16, 32, 64}) {
            double rec = 0, frac = 0;
            for (float[] q : qe) {
                Set<Integer> truth = topK(q, all, null, K);
                Integer[] order = IntStream.range(0, nl).boxed().sorted(Comparator.comparingDouble(j -> -dot(q, c[j]))).toArray(Integer[]::new);
                Set<Integer> cand = new HashSet<>();
                for (int p = 0; p < nprobe; p++) cand.addAll(lists.get(order[p]));
                int hit = 0; for (int id : truth) if (cand.contains(id)) hit++;
                rec += hit / (double) truth.size(); frac += cand.size() / (double) n;
            }
            System.out.printf("  nprobe=%d scanned=%.1f%% of blocks recall=%.3f%n", nprobe, 100 * frac / qe.length, rec / qe.length);
        }
    }

    static int nearest(float[][] c, float[] v) { int b = 0; double bd = -2; for (int j = 0; j < c.length; j++) { double d = dot(v, c[j]); if (d > bd) { bd = d; b = j; } } return b; }
}
