package io.github.andrix03.retroreplay.recording;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes the .mcpr zip: recording.tmcpr + metaData.json. Temp file + rename, never a half file. */
public final class ReplayFiles {

	private ReplayFiles() {
	}

	public static void pack(File tmcpr, File target, ReplayMetadata meta) throws IOException {
		File temp = new File(target.getPath() + ".tmp");
		try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temp))) {
			zip.putNextEntry(new ZipEntry("recording.tmcpr"));
			try (InputStream in = new FileInputStream(tmcpr)) {
				byte[] chunk = new byte[64 * 1024];
				int n;
				while ((n = in.read(chunk)) > 0) {
					zip.write(chunk, 0, n);
				}
			}
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("metaData.json"));
			zip.write(meta.toJson().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		if (target.exists() || !temp.renameTo(target)) {
			throw new IOException("Cannot move " + temp + " to " + target);
		}
	}
}
