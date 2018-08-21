/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import static com.aoindustries.aoserv.daemon.httpd.tomcat.VersionedTomcatCommon.BACKUP_EXTENSION;
import static com.aoindustries.aoserv.daemon.httpd.tomcat.VersionedTomcatCommon.BACKUP_SEPARATOR;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.IoUtils;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Helps install files.
 *
 * @author  AO Industries, Inc.
 */
public abstract class Install {

	protected final String path;

	public Install(String path) {
		this.path = path;
	}

	public String getPath() {
		return path;
	}

	public abstract void install(String optSlash, String apacheTomcatDir, UnixFile installDir, int uid, int gid, String backupSuffix) throws IOException;

	public static class Delete extends Install {

		public Delete(String path) {
			super(path);
		}

		@Override
		public void install(String optSlash, String apacheTomcatDir, UnixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			UnixFile uf = new UnixFile(installDir, path, true);
			if(uf.getStat().exists()) {
				uf.renameTo(DaemonFileUtils.findUnusedBackup(uf + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION));
			}
		}
	}

	public static class Mkdir extends Install {

		protected final int mode;

		public Mkdir(String path, int mode) {
			super(path);
			this.mode = mode;
		}

		public int getMode() {
			return mode;
		}

		@Override
		public void install(String optSlash, String apacheTomcatDir, UnixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			UnixFile uf = new UnixFile(installDir, path, true);
			DaemonFileUtils.mkdir(
				uf, mode, uid, gid,
				DaemonFileUtils.findUnusedBackup(uf + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
		}
	}

	public static class Symlink extends Install {

		protected final String target;

		public Symlink(String path, String target) {
			super(path);
			this.target = target;
		}

		public Symlink(String path) {
			this(path, null);
		}

		public String getTarget() {
			return target;
		}

		@Override
		public void install(String optSlash, String apacheTomcatDir, UnixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			UnixFile uf = new UnixFile(installDir, path, true);
			DaemonFileUtils.ln(
				(target == null)
					? ("../" + optSlash + apacheTomcatDir + "/" + path)
					: target,
				uf, uid, gid,
				DaemonFileUtils.findUnusedBackup(uf + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION)
			);
		}
	}

	public static class SymlinkAll extends Install {

		public SymlinkAll(String path) {
			super(path);
		}

		@Override
		public void install(String optSlash, String apacheTomcatDir, UnixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			UnixFile uf = new UnixFile(installDir, path, true);
			DaemonFileUtils.lnAll(
				"../" + optSlash + apacheTomcatDir + "/" + path + "/",
				uf, uid, gid,
				backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION
			);
		}
	}

	public static class Copy extends Install {

		protected final int mode;

		public Copy(String path, int mode) {
			super(path);
			this.mode = mode;
		}

		public int getMode() {
			return mode;
		}

		@Override
		public void install(String optSlash, String apacheTomcatDir, UnixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			UnixFile uf = new UnixFile(installDir, path, true);
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			try (InputStream in = new FileInputStream("/opt/" + apacheTomcatDir + "/" + path)) {
				IoUtils.copy(in, bout);
			}
			DaemonFileUtils.atomicWrite(
				uf, bout.toByteArray(), mode, uid, gid,
				DaemonFileUtils.findUnusedBackup(uf + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
				null
			);
		}
	}

	public static class Generated extends Install {

		@FunctionalInterface
		public static interface ContentGenerator {
			byte[] generateContent(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException;
		}

		protected final int mode;

		protected final ContentGenerator contentGenerator;

		public Generated(String path, int mode, ContentGenerator contentGenerator) {
			super(path);
			this.mode = mode;
			this.contentGenerator = contentGenerator;
		}

		public int getMode() {
			return mode;
		}

		public ContentGenerator getContentGenerator() {
			return contentGenerator;
		}

		@Override
		public void install(String optSlash, String apacheTomcatDir, UnixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			UnixFile uf = new UnixFile(installDir, path, true);
			DaemonFileUtils.atomicWrite(
				uf, contentGenerator.generateContent(optSlash, apacheTomcatDir, installDir), mode, uid, gid,
				DaemonFileUtils.findUnusedBackup(uf + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
				null
			);
		}
	}

	public static class ProfileScript extends Generated {

		public ProfileScript(String path, int mode) {
			super(
				path,
				mode,
				(optSlash, apacheTomcatDir, installDir) -> {
					ByteArrayOutputStream bout = new ByteArrayOutputStream();
					try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
						out.print("#!/bin/sh\n"
								+ "#\n"
								+ "# Generated by ").print(ProfileScript.class.getName()).print("\n"
								+ "#\n"
								+ "if [ \"${AOSERV_PROFILE_D:-}\" != '").print(installDir).print("/bin/profile.d' ]; then\n"
								+ "    exec env -i AOSERV_PROFILE_D='").print(installDir).print("/bin/profile.d' \"$0\" \"$@\"\n"
								+ "fi\n"
								+ ". /etc/profile\n"
								+ "\n"
								+ "exec '/opt/").print(apacheTomcatDir).print('/').print(path).print("'\n");
					}
					return bout.toByteArray();
				}
			);
		}

		public ProfileScript(String path) {
			this(path, 0700);
		}
	}
}
