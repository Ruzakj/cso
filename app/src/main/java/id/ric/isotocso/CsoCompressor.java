package id.ric.isotocso;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;

public final class CsoCompressor {
    public static final int BLOCK_SIZE = 2048;
    private static final int HEADER_SIZE = 24;
    private static final long PLAIN_FLAG = 0x80000000L;

    public interface Progress {
        void onProgress(long done, long total, long written, long startedAtMs);
    }

    public record Result(long inputBytes, long outputBytes, int blocks) {}

    public static Result compress(ContentResolver resolver, Uri input, Uri output, int level,
                                  AtomicBoolean cancelled, Progress progress) throws IOException {
        if (level < 1 || level > 9) throw new IllegalArgumentException("Level harus 1–9");
        try (ParcelFileDescriptor inPfd = resolver.openFileDescriptor(input, "r");
             ParcelFileDescriptor outPfd = resolver.openFileDescriptor(output, "rw")) {
            if (inPfd == null || outPfd == null) throw new IOException("File tidak dapat dibuka");
            long size = inPfd.getStatSize();
            if (size <= 0) throw new IOException("Ukuran ISO tidak tersedia atau kosong");
            if (size % BLOCK_SIZE != 0) throw new IOException("File bukan ISO sektor 2048-byte yang valid");
            long blockCountLong = (size + BLOCK_SIZE - 1) / BLOCK_SIZE;
            if (blockCountLong + 1 > Integer.MAX_VALUE) throw new IOException("ISO terlalu besar");
            int blocks = (int) blockCountLong;
            long tableBytes = (blocks + 1L) * 4L;
            long dataStart = HEADER_SIZE + tableBytes;
            if (dataStart >= PLAIN_FLAG) throw new IOException("Tabel indeks CSO terlalu besar");

            try (FileInputStream inputStream = new FileInputStream(inPfd.getFileDescriptor());
                 FileOutputStream outputStream = new FileOutputStream(outPfd.getFileDescriptor())) {
                FileChannel in = inputStream.getChannel();
                FileChannel out = outputStream.getChannel();
                out.truncate(0);
                writeHeader(out, size);
                writeZeros(out, tableBytes);

                int[] index = new int[blocks + 1];
                byte[] raw = new byte[BLOCK_SIZE];
                byte[] packed = new byte[BLOCK_SIZE + 64];
                Deflater deflater = new Deflater(level, true);
                long readTotal = 0;
                long written = dataStart;
                long started = System.currentTimeMillis();

                try {
                    for (int block = 0; block < blocks; block++) {
                        if (cancelled.get()) throw new CancelledException();
                        int wanted = (int) Math.min(BLOCK_SIZE, size - readTotal);
                        readFully(in, raw, wanted);
                        index[block] = checkedIndex(written);

                        deflater.reset();
                        deflater.setInput(raw, 0, wanted);
                        deflater.finish();
                        int compressed = deflater.deflate(packed);
                        boolean useCompressed = deflater.finished() && compressed < wanted;
                        if (useCompressed) {
                            writeFully(out, ByteBuffer.wrap(packed, 0, compressed));
                            written += compressed;
                        } else {
                            index[block] |= (int) PLAIN_FLAG;
                            writeFully(out, ByteBuffer.wrap(raw, 0, wanted));
                            written += wanted;
                        }
                        readTotal += wanted;
                        if ((block & 127) == 0 || block + 1 == blocks)
                            progress.onProgress(readTotal, size, written, started);
                    }
                } finally {
                    deflater.end();
                }
                index[blocks] = checkedIndex(written);
                out.position(HEADER_SIZE);
                ByteBuffer table = ByteBuffer.allocate(Math.min(64 * 1024, (blocks + 1) * 4))
                        .order(ByteOrder.LITTLE_ENDIAN);
                for (int value : index) {
                    if (table.remaining() < 4) { table.flip(); writeFully(out, table); table.clear(); }
                    table.putInt(value);
                }
                table.flip(); writeFully(out, table);
                out.force(true);
                validateIndex(out.size(), index, dataStart, written);
                return new Result(size, written, blocks);
            }
        }
    }

    private static void writeHeader(FileChannel out, long size) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        h.put((byte)'C').put((byte)'I').put((byte)'S').put((byte)'O');
        h.putInt(HEADER_SIZE).putLong(size).putInt(BLOCK_SIZE).put((byte)1).put((byte)0).putShort((short)0);
        h.flip(); writeFully(out, h);
    }

    private static void validateIndex(long actualSize, int[] index, long dataStart, long written) throws IOException {
        if (actualSize != written) throw new IOException("Ukuran output tidak konsisten");
        long lastPosition = dataStart;
        for (int i=0; i<index.length; i++) {
            long value = Integer.toUnsignedLong(index[i]) & ~PLAIN_FLAG;
            if (value < lastPosition || value > written) throw new IOException("Indeks CSO rusak pada blok " + i);
            lastPosition = value;
        }
        if (lastPosition != written) throw new IOException("Indeks akhir CSO tidak valid");
    }

    private static int checkedIndex(long offset) throws IOException {
        if (offset >= PLAIN_FLAG) throw new IOException("Output melewati batas CSO v1 (2 GiB terkompresi)");
        return (int) offset;
    }
    private static void writeZeros(FileChannel out, long count) throws IOException {
        ByteBuffer zeros = ByteBuffer.allocate(64 * 1024);
        while (count > 0) { zeros.clear(); zeros.limit((int)Math.min(zeros.capacity(), count)); writeFully(out, zeros); count -= zeros.limit(); }
    }
    private static void readFully(FileChannel ch, byte[] dst, int count) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(dst, 0, count); readFully(ch, b);
    }
    private static void readFully(FileChannel ch, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) if (ch.read(b) < 0) throw new EOFException("ISO terpotong");
    }
    private static void writeFully(FileChannel ch, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) ch.write(b);
    }
    public static final class CancelledException extends IOException {
        public CancelledException() { super("Dibatalkan"); }
    }
    private CsoCompressor() {}
}
