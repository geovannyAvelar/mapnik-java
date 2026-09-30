package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A UTFGrid: a coarse picture in which each cell names the feature drawn there, so a web map can
 * highlight a feature or show its details when the pointer is over it. Get one from
 * {@link MapnikMap#renderGrid}, send {@link #toJson()} to the browser, or look cells up here with
 * pixel coordinates of the image it accompanies.
 */
public final class UtfGrid {
    private final String json;
    private final int resolution;
    private final int width;
    private final int height;
    private final int[][] cells;            // [row][column] -> index into keys
    private final List<String> keys;        // index 0 is always the empty key: no feature
    private final Map<String, Map<String, Object>> data;

    private UtfGrid(String json, int resolution, int width, int height, int[][] cells, List<String> keys,
                    Map<String, Map<String, Object>> data) {
        this.json = json;
        this.resolution = resolution;
        this.width = width;
        this.height = height;
        this.cells = cells;
        this.keys = keys;
        this.data = data;
    }

    /** Read a UTFGrid from JSON, given the size of the image it covers and how many pixels make a cell. */
    @SuppressWarnings("unchecked")
    public static UtfGrid parse(String json, int resolution, int width, int height) {
        if (resolution < 1) {
            throw new IllegalArgumentException("resolution must be at least 1: " + resolution);
        }
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("a UTFGrid is a JSON object");
        }
        Map<String, Object> o = (Map<String, Object>) root;
        if (!(o.get("grid") instanceof List) || !(o.get("keys") instanceof List)) {
            throw new IllegalArgumentException("a UTFGrid needs \"grid\" and \"keys\" arrays");
        }
        List<String> keys = new ArrayList<>();
        for (Object k : (List<Object>) o.get("keys")) {
            if (!(k instanceof String)) {
                throw new IllegalArgumentException("UTFGrid keys must be strings");
            }
            keys.add((String) k);
        }
        List<Object> rows = (List<Object>) o.get("grid");
        int[][] cells = new int[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            if (!(rows.get(r) instanceof String)) {
                throw new IllegalArgumentException("UTFGrid rows must be strings");
            }
            int[] codePoints = ((String) rows.get(r)).codePoints().toArray();
            cells[r] = new int[codePoints.length];
            for (int c = 0; c < codePoints.length; c++) {
                int code = codePoints[c];
                if (code >= 93) {
                    code--;
                }
                if (code >= 35) {
                    code--;
                }
                int index = code - 32;
                if (index < 0 || index >= keys.size()) {
                    throw new IllegalArgumentException("UTFGrid cell refers to key " + index + " but there are " + keys.size());
                }
                cells[r][c] = index;
            }
        }
        Map<String, Map<String, Object>> data = new LinkedHashMap<>();
        Object d = o.get("data");
        if (d instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) d).entrySet()) {
                Map<String, Object> attrs = new LinkedHashMap<>();
                if (e.getValue() instanceof Map) {
                    for (Map.Entry<String, Object> a : ((Map<String, Object>) e.getValue()).entrySet()) {
                        Object v = a.getValue();
                        if (v instanceof Double && (Double) v == Math.rint((Double) v) && Math.abs((Double) v) < 1e15) {
                            v = Long.valueOf(((Double) v).longValue());
                        }
                        attrs.put(a.getKey(), v);
                    }
                }
                data.put(e.getKey(), Collections.unmodifiableMap(attrs));
            }
        }
        return new UtfGrid(json, resolution, width, height, cells, Collections.unmodifiableList(keys),
            Collections.unmodifiableMap(data));
    }

    /** The JSON, ready to send to a web map. */
    public String toJson() { return json; }

    /** Pixels of the image per cell of the grid. */
    public int resolution() { return resolution; }

    /** Size in pixels of the image this grid covers. */
    public int imageWidth() { return width; }

    public int imageHeight() { return height; }

    /** Cells across and down. */
    public int columns() { return cells.length == 0 ? 0 : cells[0].length; }

    public int rows() { return cells.length; }

    /** The names of the features that appear in the grid, in order of first appearance. */
    public List<String> keys() { return keys.subList(1, keys.size()); }

    /** The attribute data for one feature, or null if there is none for that key. */
    public Map<String, Object> data(String key) { return data.get(key); }

    /** The key of the feature at a pixel of the image, or an empty string where there is none. */
    public String keyAt(int pixelX, int pixelY) {
        if (pixelX < 0 || pixelY < 0 || pixelX >= width || pixelY >= height) {
            throw new IndexOutOfBoundsException("pixel (" + pixelX + ", " + pixelY + ") outside " + width + "x" + height);
        }
        int row = Math.min(pixelY / resolution, cells.length - 1);
        int col = Math.min(pixelX / resolution, cells[row].length - 1);
        return keys.get(cells[row][col]);
    }

    /** The attributes of the feature at a pixel, or an empty map where there is none. */
    public Map<String, Object> attributesAt(int pixelX, int pixelY) {
        Map<String, Object> d = data.get(keyAt(pixelX, pixelY));
        return d == null ? Collections.<String, Object>emptyMap() : d;
    }

    @Override
    public String toString() {
        return "UtfGrid[" + columns() + "x" + rows() + " cells, " + keys().size() + " features]";
    }
}
