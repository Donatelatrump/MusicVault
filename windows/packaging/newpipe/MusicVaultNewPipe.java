import com.grack.nanojson.JsonWriter;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.schabi.newpipe.extractor.*;
import org.schabi.newpipe.extractor.downloader.*;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.playlist.*;
import org.schabi.newpipe.extractor.stream.*;

/** Desktop adapter for the same NewPipeExtractor 0.26.5 used by Android. */
public final class MusicVaultNewPipe {
    private static final class HttpDownloader extends Downloader {
        @Override public Response execute(Request request) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(request.url()).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod(request.httpMethod());
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; rv:140.0) Gecko/140.0 Firefox/140.0");
            Request.getHeadersFromLocalization(request.localization()).forEach((key, values) -> {
                for (String value : values) connection.addRequestProperty(key, value);
            });
            request.headers().forEach((key, values) -> {
                connection.setRequestProperty(key, values.isEmpty() ? "" : values.get(0));
                for (int i = 1; i < values.size(); i++) connection.addRequestProperty(key, values.get(i));
            });
            connection.setRequestProperty("Accept-Encoding", "gzip");
            try {
                byte[] body = request.dataToSend();
                if (body != null) {
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) { out.write(body); }
                }
                int code = connection.getResponseCode();
                InputStream raw = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = "";
                if (raw != null) {
                    try (InputStream in = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                            ? new GZIPInputStream(raw) : raw) {
                        byte[] data = in.readNBytes(16 * 1024 * 1024 + 1);
                        if (data.length > 16 * 1024 * 1024) throw new IOException("YouTube response too large");
                        text = new String(data, StandardCharsets.UTF_8);
                    }
                }
                Map<String, List<String>> headers = new HashMap<>();
                connection.getHeaderFields().forEach((key, values) -> { if (key != null) headers.put(key, values); });
                return new Response(code, connection.getResponseMessage(), headers, text, connection.getURL().toString());
            } finally { connection.disconnect(); }
        }
    }

    private static String thumbnail(List<Image> images) {
        return images.stream().max(Comparator.comparingLong(i ->
                (long) Math.max(0, i.getHeight()) * Math.max(0, i.getWidth())))
                .map(Image::getUrl).orElse("");
    }

    private static Map<String, Object> track(StreamInfoItem item) throws Exception {
        String id = ServiceList.YouTube.getStreamLHFactory().getId(item.getUrl());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("title", item.getName());
        row.put("uploader", item.getUploaderName()); row.put("duration", Math.max(0, item.getDuration()));
        String image = thumbnail(item.getThumbnails());
        row.put("thumbnail", image.isBlank() ? "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg" : image);
        row.put("url", item.getUrl());
        return row;
    }

    private static int audioScore(AudioStream stream) {
        int rate = stream.getAverageBitrate() > 0 ? stream.getAverageBitrate() : Math.max(0, stream.getBitrate());
        int value = rate >= 96 && rate <= 192 ? 400 - Math.abs(160 - rate)
                : rate > 0 && rate < 96 ? 180 + rate : rate > 192 ? 120 - Math.min(100, (rate - 192) / 8) : 0;
        String format = stream.getFormat() == null ? "" : stream.getFormat().name();
        return value + (format.equals("M4A") ? 80 : format.equals("WEBMA_OPUS") || format.equals("OPUS") ? 70
                : format.equals("WEBMA") || format.equals("WEBM") ? 60 : 20);
    }

    public static void main(String[] arguments) {
        try {
            NewPipe.init(new HttpDownloader());
            String mode = arguments[0];
            if (mode.equals("health")) {
                System.out.println("{\"extractor\":\"NewPipeExtractor\",\"version\":\"0.26.5\"}");
                return;
            }
            String query = arguments[1];
            int limit = arguments.length > 2 ? Integer.parseInt(arguments[2]) : 25;
            List<Map<String, Object>> rows = new ArrayList<>();
            if (mode.equals("search") || mode.equals("playlists")) {
                var handler = mode.equals("playlists")
                        ? ServiceList.YouTube.getSearchQHFactory().fromQuery(query, List.of("playlists"), "")
                        : ServiceList.YouTube.getSearchQHFactory().fromQuery(query);
                SearchInfo info = SearchInfo.getInfo(ServiceList.YouTube, handler);
                for (InfoItem item : info.getRelatedItems()) {
                    if (mode.equals("search") && item instanceof StreamInfoItem) rows.add(track((StreamInfoItem) item));
                    if (mode.equals("playlists") && item instanceof PlaylistInfoItem) {
                        PlaylistInfoItem playlist = (PlaylistInfoItem) item;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", ServiceList.YouTube.getPlaylistLHFactory().getId(playlist.getUrl()));
                        row.put("title", playlist.getName()); row.put("uploader", playlist.getUploaderName());
                        row.put("thumbnail", thumbnail(playlist.getThumbnails()));
                        row.put("url", playlist.getUrl()); row.put("playlist_count", playlist.getStreamCount());
                        rows.add(row);
                    }
                    if (rows.size() >= Math.min(limit, 30)) break;
                }
                System.out.println(JsonWriter.string(rows));
            } else if (mode.equals("playlist")) {
                PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, query);
                for (StreamInfoItem item : info.getRelatedItems()) {
                    if (rows.size() >= limit) break;
                    rows.add(track(item));
                }
                Page next = info.getNextPage();
                while (rows.size() < limit && Page.isValid(next)) {
                    var page = PlaylistInfo.getMoreItems(ServiceList.YouTube, query, next);
                    for (StreamInfoItem item : page.getItems()) {
                        if (rows.size() >= limit) break;
                        rows.add(track(item));
                    }
                    next = page.getNextPage();
                }
                System.out.println(JsonWriter.string(rows));
            } else if (mode.equals("resolve")) {
                StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, query);
                AudioStream stream = info.getAudioStreams().stream().filter(s -> s.isUrl()
                        && s.getContent().startsWith("http") && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP)
                        .max(Comparator.comparingInt(MusicVaultNewPipe::audioScore)).orElseThrow();
                System.out.println(JsonWriter.string(Map.of("url", stream.getContent())));
            } else throw new IllegalArgumentException("Unknown NewPipe operation");
        } catch (Exception error) {
            System.err.println(error.getClass().getSimpleName() + ": " + error.getMessage());
            System.exit(1);
        }
    }
}
