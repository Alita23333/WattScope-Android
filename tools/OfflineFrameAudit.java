import io.github.alita23333.wattscope.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;
import org.json.*;

/** Runs the actual compiled Kotlin statistics on a local raw recording; never accesses ADB. */
class OfflineFrameAudit {
    public static void main(String[] args) throws Exception {
        var calibration = new FrameCalibration();
        var groups = new ArrayList<List<Long>>();
        var frames = new ArrayList<Frame>();
        var segments = new ArrayList<long[]>();
        var metrics = new LinkedHashMap<String, Metric>();
        int generation = 0;
        String previousLayer = null;
        int previousGeneration = -1;
        long blocks = 0;
        var digest = MessageDigest.getInstance("SHA-256");
        try (var raw = new BufferedInputStream(Files.newInputStream(Path.of(args[0])));
             var input = new DataInputStream(new java.security.DigestInputStream(raw, digest))) {
            while (true) {
                int first = input.read();
                if (first < 0) break;
                int magic = (first << 24) | (input.readUnsignedByte() << 16) | (input.readUnsignedByte() << 8) | input.readUnsignedByte();
                int length = input.readInt();
                long expected = Integer.toUnsignedLong(input.readInt());
                if (magic != 0x57534e31 || length <= 0 || length > 16777216) throw new IOException("Invalid raw block");
                byte[] data = input.readNBytes(length);
                var crc = new CRC32(); crc.update(data);
                if (data.length != length || crc.getValue() != expected) throw new IOException("CRC or incomplete block");
                blocks++;
                for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
                    String[] p = line.split("\\|", 6);
                    switch (p[0]) {
                        case "D" -> {
                            if (p.length >= 5) metrics.put(p[1], new Metric(p[1], p[2], p[3], p[4], p.length > 5 ? p[5] : ""));
                        }
                        case "B" -> segments.add(new long[]{Long.parseLong(p[1]), Long.MAX_VALUE});
                        case "P" -> { if (!segments.isEmpty()) segments.get(segments.size() - 1)[1] = Long.parseLong(p[1]); }
                        case "E" -> { if (Set.of("collector", "layer", "frame_error").contains(p[2])) generation++; }
                        case "F" -> {
                            long ns = Long.parseLong(p[1]);
                            String layer = p.length > 3 ? p[3] : "";
                            frames.add(new Frame(ns, Long.parseLong(p[2]), layer, generation));
                        }
                    }
                }
            }
        }
        int previousSegment = -1;
        for (Frame frame : frames) {
            int segment = -1;
            for (int i = 0; i < segments.size(); i++) {
                long[] bounds = segments.get(i);
                if (frame.getNs() >= bounds[0] && frame.getNs() <= bounds[1]) segment = i;
            }
            if (segment < 0) continue;
            if (segment != previousSegment) calibration.boundary();
            calibration.add(frame);
            if (segment != previousSegment || !Objects.equals(previousLayer, frame.getLayer()) || previousGeneration != frame.getGeneration()) groups.add(new ArrayList<>());
            groups.get(groups.size() - 1).add(frame.getNs());
            previousSegment = segment; previousLayer = frame.getLayer(); previousGeneration = frame.getGeneration();
        }
        var summary = Statistics.INSTANCE.frameStats(groups, calibration.result());
        var baseline = summary.getFrameBaseline();
        var clusters = new JSONArray();
        for (var cluster : CpuTopology.INSTANCE.discover(metrics.values()))
            clusters.put(new JSONObject().put("policy", cluster.getPolicy()).put("cores", cluster.getCores()).put("label", cluster.getLabel()));
        var report = new JSONObject().put("algorithmVersion", Statistics.ALGORITHM_VERSION)
            .put("rawSha256", HexFormat.of().formatHex(digest.digest())).put("crcValid", true).put("committedBlocks", blocks)
            .put("frameCount", summary.getFrameCount()).put("avgFps", summary.getAvgFps())
            .put("low1", summary.getLow1()).put("low5", summary.getLow5()).put("p95Ms", summary.getP95()).put("p99Ms", summary.getP99())
            .put("slowFrames", summary.getSlow() == null ? JSONObject.NULL : summary.getSlow())
            .put("severeFrames", summary.getSevere() == null ? JSONObject.NULL : summary.getSevere())
            .put("baselineFps", baseline.getFps() == null ? JSONObject.NULL : baseline.getFps())
            .put("budgetMs", baseline.getBudgetMs() == null ? JSONObject.NULL : baseline.getBudgetMs())
            .put("observedSeconds", baseline.getObservedSeconds()).put("intervalCount", baseline.getIntervalCount()).put("cpuClusters", clusters);
        if (args.length > 1) Files.writeString(Path.of(args[1]), report.toString(2));
        System.out.println(report.toString(2));
    }
}
