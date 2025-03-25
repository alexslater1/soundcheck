package app.soundcheck.media.internal.transcode;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * Parses the JSON of {@code ffprobe -v error -print_format json -show_format -show_streams} and applies
 * the rejection rules: no video stream, longer than 61 s, wider or taller than 4096, or a
 * codec ffprobe could not identify. Pure: no process is started here.
 */
public final class ProbeParser {

    public static final long MAX_DURATION_MS = 61_000;
    public static final int MAX_DIMENSION = 4096;
    private static final Set<String> NOT_DECODABLE = Set.of("", "unknown", "none");

    private ProbeParser() {}

    /** @throws MediaRejectedException with the reason, when the file must not be transcoded */
    public static ProbeResult parse(JsonNode probe) {
        JsonNode video = null;
        JsonNode audio = null;
        for (JsonNode stream : probe.path("streams")) {
            String type = stream.path("codec_type").asText("");
            // Cover art is reported as a video stream with the attached_pic disposition: not a video.
            if (type.equals("video")
                    && video == null
                    && stream.path("disposition").path("attached_pic").asInt(0) == 0) {
                video = stream;
            } else if (type.equals("audio") && audio == null) {
                audio = stream;
            }
        }
        if (video == null) {
            throw new MediaRejectedException("no video stream");
        }
        String codec = video.path("codec_name").asText("");
        if (NOT_DECODABLE.contains(codec)) {
            throw new MediaRejectedException("video codec not recognised");
        }
        int width = video.path("width").asInt(0);
        int height = video.path("height").asInt(0);
        if (width <= 0 || height <= 0) {
            throw new MediaRejectedException("video has no dimensions");
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new MediaRejectedException(
                    "larger than " + MAX_DIMENSION + " pixels on one side (" + width + "x" + height + ")");
        }
        long durationMs = durationMs(probe, video);
        if (durationMs <= 0) {
            throw new MediaRejectedException("duration unknown");
        }
        if (durationMs > MAX_DURATION_MS) {
            throw new MediaRejectedException("longer than 60 seconds (" + durationMs / 1000.0 + " s)");
        }
        return new ProbeResult(
                width,
                height,
                rotation(video),
                durationMs,
                codec,
                audio != null,
                audio == null ? null : audio.path("codec_name").asText(null),
                frameRate(video.path("avg_frame_rate").asText("")));
    }

    /** The container's duration, else the video stream's; ffprobe prints seconds as a decimal string. */
    private static long durationMs(JsonNode probe, JsonNode video) {
        for (JsonNode node : new JsonNode[] {probe.path("format").path("duration"), video.path("duration")}) {
            try {
                double seconds = node.isNumber() ? node.asDouble() : Double.parseDouble(node.asText(""));
                if (seconds > 0) {
                    return Math.round(seconds * 1000);
                }
            } catch (NumberFormatException e) {
                // try the next place
            }
        }
        return 0;
    }

    /**
     * Phones store portrait video as landscape pixels plus a rotation: newer files in a "Display Matrix"
     * side-data entry (ffprobe reports it counter-clockwise, e.g. -90), older ones in a {@code rotate} tag.
     * Returned as clockwise degrees: 0, 90, 180 or 270.
     */
    static int rotation(JsonNode video) {
        for (JsonNode sideData : video.path("side_data_list")) {
            if (sideData.has("rotation")) {
                return normalise(-sideData.path("rotation").asInt(0));
            }
        }
        String tag = video.path("tags").path("rotate").asText("");
        if (!tag.isBlank()) {
            try {
                return normalise(Integer.parseInt(tag.strip()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static int normalise(int degrees) {
        int turned = ((degrees % 360) + 360) % 360;
        return (turned / 90) * 90 % 360; // snap to a quarter turn
    }

    /** "30000/1001" → 29.97; null when ffprobe has no figure ("0/0"). */
    private static Double frameRate(String ratio) {
        String[] parts = ratio.split("/");
        try {
            if (parts.length == 2 && Double.parseDouble(parts[1]) != 0) {
                return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        return null;
    }
}
