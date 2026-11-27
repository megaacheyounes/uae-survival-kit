package ae.fieldguide;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** CPU-only English Model2Vec encoder and cosine search over bundled guide steps. */
public final class SemanticIndex {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}\\s]");
    private static final Pattern ACCENT = Pattern.compile("\\p{Mn}+");
    private final Map<String,Integer> vocab;
    private final byte[] weights;
    private final float[] scales;
    private final int dims;
    private final String[] keys;
    private final float[][] passages;

    public SemanticIndex(InputStream input) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(input, 65536))) {
            if (in.readInt() != 0x46475331) throw new IOException("Unknown passage index");
            int tokens = in.readInt();
            dims = in.readInt();
            int count = in.readInt();
            if (tokens != 29528 || dims != 64 || count < 1 || count > 10000) throw new IOException("Invalid passage index");
            vocab = new HashMap<>(tokens * 2);
            scales = new float[tokens];
            weights = new byte[tokens * dims];
            for (int i = 0; i < tokens; ++i) {
                vocab.put(readString(in), i);
                scales[i] = in.readFloat();
                in.readFully(weights, i * dims, dims);
            }
            keys = new String[count];
            passages = new float[count][];
            for (int i = 0; i < count; ++i) {
                keys[i] = readString(in);
                int variants = in.readUnsignedShort();
                if (variants == 0 || variants > 128) throw new IOException("Invalid passage vectors");
                float[] vectors = new float[variants * dims];
                for (int j = 0; j < vectors.length; ++j) vectors[j] = in.readFloat();
                passages[i] = vectors;
            }
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] data = new byte[in.readUnsignedShort()];
        in.readFully(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private void addWord(String word, float[] vector) {
        if (word.length() > 100) return;
        int[] parts = new int[word.length()];
        int used = 0;
        for (int i = 0; i < word.length();) {
            int end = word.length();
            Integer id = null;
            while (end > i) {
                id = vocab.get((i == 0 ? "" : "##") + word.substring(i, end));
                if (id != null) break;
                --end;
            }
            if (id == null) return;
            parts[used++] = id;
            i = end;
        }
        for (int n = 0; n < used; ++n) {
            int id = parts[n];
            for (int j = 0; j < dims; ++j) vector[j] += weights[id * dims + j] * scales[id];
        }
    }

    public static final class Hit {
        public final String key;
        public final float score;
        Hit(String key, float score) { this.key = key; this.score = score; }
    }

    public List<Hit> search(String question, int limit) {
        String plain = Normalizer.normalize(question.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        Matcher words = WORD.matcher(ACCENT.matcher(plain).replaceAll(""));
        float[] query = new float[dims];
        while (words.find()) addWord(words.group(), query);
        double norm = 0;
        for (float v : query) norm += v * v;
        if (norm == 0) return Collections.emptyList();
        float inv = (float)(1 / Math.sqrt(norm));
        for (int j = 0; j < dims; ++j) query[j] *= inv;
        List<Hit> hits = new ArrayList<>(keys.length);
        for (int i = 0; i < keys.length; ++i) {
            float best = -1;
            float[] vectors = passages[i];
            for (int k = 0; k < vectors.length; k += dims) {
                float dot = 0;
                for (int j = 0; j < dims; ++j) dot += query[j] * vectors[k + j];
                best = Math.max(best, dot);
            }
            hits.add(new Hit(keys[i], best));
        }
        hits.sort((a,b) -> Float.compare(b.score, a.score));
        return hits.subList(0, Math.min(limit, hits.size()));
    }
}
