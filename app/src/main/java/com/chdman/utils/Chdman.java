package com.chdman.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class Chdman {
    static { System.loadLibrary("chdman"); }

    private native void createdvd(String input, String output);

    public void createDvd(File input, File output) throws IOException {
        if (output.exists() && !output.delete())
            throw new IOException("File CHD sementara tidak dapat dibersihkan");
        createdvd(input.getAbsolutePath(), output.getAbsolutePath());
        if (!output.isFile() || output.length() < 124)
            throw new IOException("Engine CHD gagal membuat output");
        byte[] magic = new byte[8];
        try (FileInputStream in = new FileInputStream(output)) {
            if (in.read(magic) != magic.length || !"MComprHD".equals(new String(magic, StandardCharsets.US_ASCII)))
                throw new IOException("Header CHD tidak valid");
        }
    }
}
