package com.cdnworkbench.cdn;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * Wraps a real MP4 file and divides it into CDN-style byte-range segments.
 *
 * Duration is parsed from the ISO 14496-12 moov/mvhd box.  If the file is
 * not FastStart or mvhd cannot be found within the first 64 MB, duration is
 * estimated from file size at an assumed 2 Mbps average bitrate.
 *
 * MEMORY NOTE: each segment's cached payload is capped at MAX_SERVE_BYTES
 * (64 KB).  This is intentional — the cache only needs enough bytes to
 * simulate realistic hit/miss/eviction behaviour, not the full-resolution
 * segment.  Without this cap, edgeNodes x cacheSize x fullSegmentSize can
 * blow past the JVM heap in seconds (this is what caused the original
 * OutOfMemoryError).  Real playback uses Desktop.open() on the source file
 * directly, which doesn't go through the cache at all.
 *
 * FileChannel.read(ByteBuffer, position) is specified to be safe for
 * concurrent calls from different threads, so all UserSession virtual
 * threads can call getSegmentBytes() simultaneously without locking.
 */
public final class VideoFile implements Closeable {

    /** Per-segment cache payload cap — bounds total memory, not playback quality. */
    public  static final int  MAX_SERVE_BYTES  = 64 * 1024;          // 64 KB
    private static final long SCAN_LIMIT_BYTES = 64L * 1024 * 1024;  // mvhd scan window

    private final String      name;
    private final File        sourceFile;
    private final long        fileSizeBytes;
    private final double      durationSeconds;
    private final int         segmentCount;
    private final long[]      segmentOffsets;
    private final int[]       segmentLengths;
    private final FileChannel channel;

    private VideoFile(String name, File sourceFile, long fileSize, double duration,
                      int segCount, long[] offsets, int[] lengths, FileChannel ch) {
        this.name            = name;
        this.sourceFile      = sourceFile;
        this.fileSizeBytes   = fileSize;
        this.durationSeconds = duration;
        this.segmentCount    = segCount;
        this.segmentOffsets  = offsets;
        this.segmentLengths  = lengths;
        this.channel         = ch;
    }

    // ── Factory ────────────────────────────────────────────────────────────

    public static VideoFile load(File file, int segDurationSecs) throws IOException {
        long fileSize = file.length();
        if (fileSize == 0) throw new IOException("File is empty");

        double duration = parseMvhdDuration(file);
        if (duration <= 0) {
            duration = (fileSize * 8.0) / 2_000_000.0;   // fallback @ 2 Mbps
        }

        int numSegs  = Math.max(1, (int) Math.ceil(duration / segDurationSecs));
        long segSize = fileSize / numSegs;

        long[] offsets = new long[numSegs];
        int[]  lengths = new int [numSegs];
        for (int i = 0; i < numSegs; i++) {
            offsets[i] = (long) i * segSize;
            long end   = (i == numSegs - 1) ? fileSize : (long)(i + 1) * segSize;
            // Cap cached payload size — see class-level MEMORY NOTE
            lengths[i] = (int) Math.min(end - offsets[i], MAX_SERVE_BYTES);
        }

        FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        return new VideoFile(file.getName(), file, fileSize, duration,
                             numSegs, offsets, lengths, ch);
    }

    // ── Segment serving (concurrent-safe) ──────────────────────────────────

    public byte[] getSegmentBytes(int index) throws IOException {
        if (index < 0 || index >= segmentCount) return new byte[0];
        int  len    = segmentLengths[index];
        long offset = segmentOffsets[index];
        ByteBuffer buf = ByteBuffer.allocate(len);
        int read = channel.read(buf, offset);   // thread-safe positioned read
        if (read <= 0) return new byte[0];
        buf.flip();
        byte[] result = new byte[buf.limit()];
        buf.get(result);
        return result;
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public String getName()            { return name; }
    public File   getSourceFile()      { return sourceFile; }
    public double getDurationSeconds() { return durationSeconds; }
    public int    getSegmentCount()    { return segmentCount; }
    public long   getFileSizeBytes()   { return fileSizeBytes; }

    public String getVideoId() {
        return name.replaceAll("\\.[^.]+$", "")
                   .replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public void close() throws IOException { channel.close(); }

    // ── Minimal ISO 14496-12 box parser (mvhd only) ────────────────────────

    private static double parseMvhdDuration(File file) {
        try (var fis = new FileInputStream(file);
             var dis = new DataInputStream(new BufferedInputStream(fis, 65_536))) {

            long pos   = 0;
            long limit = Math.min(file.length(), SCAN_LIMIT_BYTES);

            while (pos + 8 <= limit) {
                long size = readU32(dis); pos += 4;
                byte[] tb = new byte[4];
                dis.readFully(tb);        pos += 4;
                String type = new String(tb, "ISO-8859-1");

                long headerLen = 8;
                if (size == 1) {
                    size = dis.readLong(); pos += 8; headerLen = 16;
                } else if (size == 0) {
                    size = limit;
                }
                long payloadLen = size - headerLen;

                if (type.equals("mvhd")) {
                    return readMvhd(dis);
                } else if (isContainerBox(type)) {
                    // descend: mvhd is the first child of moov in FastStart files
                } else {
                    skipFully(dis, payloadLen);
                    pos += payloadLen;
                }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private static double readMvhd(DataInputStream dis) throws IOException {
        int version = dis.readByte() & 0xFF;
        dis.skipBytes(3);
        if (version == 1) {
            dis.skipBytes(8); dis.skipBytes(8);
            long timescale = readU32(dis);
            long duration  = dis.readLong();
            return timescale > 0 ? (double) duration / timescale : -1;
        } else {
            dis.skipBytes(4); dis.skipBytes(4);
            long timescale = readU32(dis);
            long duration  = readU32(dis);
            return timescale > 0 ? (double) duration / timescale : -1;
        }
    }

    private static boolean isContainerBox(String type) {
        return switch (type) {
            case "moov", "trak", "mdia", "minf", "stbl", "udta", "edts", "dinf" -> true;
            default -> false;
        };
    }

    private static long readU32(DataInputStream dis) throws IOException {
        return ((long)(dis.readByte() & 0xFF) << 24)
             | ((long)(dis.readByte() & 0xFF) << 16)
             | ((long)(dis.readByte() & 0xFF) <<  8)
             |  (long)(dis.readByte() & 0xFF);
    }

    private static void skipFully(DataInputStream dis, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long s = dis.skip(remaining);
            if (s <= 0) break;
            remaining -= s;
        }
    }
}
