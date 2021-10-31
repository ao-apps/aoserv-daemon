/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2018, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.io.IoUtils;
import static com.aoindustries.aoserv.daemon.httpd.tomcat.VersionedTomcatCommon.BACKUP_EXTENSION;
import static com.aoindustries.aoserv.daemon.httpd.tomcat.VersionedTomcatCommon.BACKUP_SEPARATOR;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
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

	protected Install(String path) {
		this.path = path;
	}

	public String getPath() {
		return path;
	}

	public abstract void install(String optSlash, String apacheTomcatDir, PosixFile installDir, int uid, int gid, String backupSuffix) throws IOException;

	public static class Delete extends Install {

		public Delete(String path) {
			super(path);
		}

		@Override
		public void install(String optSlash, String apacheTomcatDir, PosixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			PosixFile uf = new PosixFile(installDir, path, true);
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
		public void install(String optSlash, String apacheTomcatDir, PosixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			PosixFile uf = new PosixFile(installDir, path, true);
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
		public void install(String optSlash, String apacheTomcatDir, PosixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			PosixFile uf = new PosixFile(installDir, path, true);
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
		public void install(String optSlash, String apacheTomcatDir, PosixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			PosixFile uf = new PosixFile(installDir, path, true);
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
		public void install(String optSlash, String apacheTomcatDir, PosixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			PosixFile uf = new PosixFile(installDir, path, true);
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
			byte[] generateContent(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException;
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
		public void install(String optSlash, String apacheTomcatDir, PosixFile installDir, int uid, int gid, String backupSuffix) throws IOException {
			PosixFile uf = new PosixFile(installDir, path, true);
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
								+ "\n"
								+ "# Reset environment\n"
								+ "if [ \"${AOSERV_PROFILE_RESET:-}\" != 'true' ]; then\n"
								+ "    exec env -i AOSERV_PROFILE_RESET='true' \"$0\" \"$@\"\n"
								+ "fi\n"
								+ "unset AOSERV_PROFILE_RESET\n"
								+ "\n"
								+ "# Load application environment\n"
								+ "export AOSERV_PROFILE_D='").print(installDir).print("/bin/profile.d'\n"
								+ ". /etc/profile\n"
								+ "\n"
								+ "exec '/opt/").print(apacheTomcatDir).print('/').print(path).print("' \"$@\"\n");
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
