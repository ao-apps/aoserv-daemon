/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008, 2009, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.util;

import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.io.FileUtils;
import com.aoapps.lang.io.IoUtils;
import com.aoapps.tempfiles.TempFile;
import com.aoapps.tempfiles.TempFileContext;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.ConcurrentModificationException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reusable file utilities.
 *
 * @author  AO Industries, Inc.
 */
public final class DaemonFileUtils {

	/** Make no instances. */
	private DaemonFileUtils() {throw new AssertionError();}

	private static final Logger logger = Logger.getLogger(DaemonFileUtils.class.getName());

	/**
	 * Copies a resource to the provided output stream.
	 */
	public static void copyResource(Class<?> clazz, String resource, OutputStream out) throws IOException {
		try (InputStream in = clazz.getResourceAsStream(resource)) {
			if(in==null) throw new IOException("Unable to find resource: "+resource);
			IoUtils.copy(in, out);
		}
	}

	/**
	 * Copies a resource to the provided filename, will not overwrite any existing file.
	 *
	 * TODO: Copy to a temp file and rename into place.
	 */
	public static void copyResource(Class<?> clazz, String resource, String filename, int uid, int gid, int mode, int uid_min, int gid_min) throws IOException {
		try (OutputStream out = new PosixFile(filename).getSecureOutputStream(uid, gid, mode, false, uid_min, gid_min)) {
			copyResource(clazz, resource, out);
		}
	}

	/**
	 * Creates a symbolic link.  If the link exists, verifies and updates the target, uid, and gid.
	 * To aid in debugging, writes the filename and target to System.err if an <code>IOException</code> occurs;
	 * exception is then rethrown.
	 *
	 * @return  {@code true} if any modification was made
	 */
	public static boolean ln(String target, String filename, int uid, int gid) throws IOException {
		return ln(target, new PosixFile(filename), uid, gid, null);
	}

	/**
	 * Creates a symbolic link.  If the link exists, verifies and updates the target, uid, and gid.
	 * To aid in debugging, writes the filename and target to System.err if an <code>IOException</code> occurs;
	 * exception is then rethrown.
	 *
	 * @param  backup  If exists but is not a symbolic link or has a mismatched target, the existing is renamed to backup
	 *
	 * @return  {@code true} if any modification was made
	 */
	public static boolean ln(String target, String filename, int uid, int gid, PosixFile backup) throws IOException {
		return ln(target, new PosixFile(filename), uid, gid, backup);
	}

	/**
	 * Creates a symbolic link.  If the link exists, verifies and updates the target, uid, and gid.
	 * To aid in debugging, writes the filename and target to System.err if an <code>IOException</code> occurs;
	 * exception is then rethrown.
	 *
	 * @return  {@code true} if any modification was made
	 */
	public static boolean ln(String target, PosixFile uf, int uid, int gid) throws IOException {
		return ln(target, uf, uid, gid, null);
	}

	/**
	 * Creates a symbolic link.  If the link exists, verifies and updates the target, uid, and gid.
	 * To aid in debugging, writes the filename and target to System.err if an <code>IOException</code> occurs;
	 * exception is then rethrown.
	 *
	 * @param  backup  If exists but is not a symbolic link or has a mismatched target, the existing is renamed to backup
	 *
	 * @return  {@code true} if any modification was made
	 */
	public static boolean ln(String target, PosixFile uf, int uid, int gid, PosixFile backup) throws IOException {
		try {
			boolean modified = false;
			Stat ufStat = uf.getStat();
			if(!ufStat.exists()) {
				uf.symLink(target);
				ufStat = uf.getStat();
				modified = true;
			} else {
				if(!ufStat.isSymLink()) {
					if(backup == null) {
						throw new IOException("File exists and is not a symbolic link: " + uf.getPath());
					}
					uf.renameTo(backup);
					uf.symLink(target);
					ufStat = uf.getStat();
					modified = true;
				} else if(!target.equals(uf.readLink())) {
					if(backup == null) {
						uf.delete();
					} else {
						uf.renameTo(backup);
					}
					uf.symLink(target);
					ufStat = uf.getStat();
					modified = true;
				}
			}
			if(ufStat.getUid() != uid || ufStat.getGid() != gid) {
				uf.chown(uid, gid);
				modified = true;
			}
			return modified;
		} catch (IOException e) {
			System.err.println("ln: filename: "+uf.getPath()+"   destination: "+target);
			throw e;
		}
	}

	/**
	 * Creates symbolic links to all items in a directory, updating any existing link targets and ownership.
	 *
	 * @return  {@code true} if any modification was made
	 *
	 * @see  #ln(String,String,int,int)
	 */
	public static boolean lnAll(String targetBase, String srcBase, int uid, int gid) throws IOException {
		boolean modified = false;
		String[] destinations=new PosixFile(targetBase).list();
		for (String destination : destinations) {
			if(ln(targetBase + destination, srcBase + destination, uid, gid)) modified = true;
		}
		return modified;
	}

	/**
	 * Creates symbolic links to all items in a directory, updating any existing link targets and ownership.
	 * Also, any files that are not found in the target are also renamed to backup.
	 *
	 * @return  {@code true} if any modification was made
	 *
	 * @see  #ln(java.lang.String, com.aoapps.io.posix.PosixFile, int, int, com.aoapps.io.posix.PosixFile)
	 */
	public static boolean lnAll(String targetBase, PosixFile src, int uid, int gid, String backupSuffix, String backupSeparator, String backupExtension) throws IOException {
		boolean modified = false;
		String[] destinations = new PosixFile(targetBase).list();
		for (String destination : destinations) {
			PosixFile symlink = new PosixFile(src, destination, false);
			if(
				ln(
					targetBase + destination,
					symlink,
					uid,
					gid,
					findUnusedBackup(symlink + backupSuffix, backupSeparator, backupExtension)
				)
			) modified = true;
		}
		// Find any files that are not a proper destination and are not already a backup extensions
		for(String filename : src.list()) {
			// Skip if is already a backup
			if(!filename.endsWith(backupExtension)) {
				boolean found = false;
				for(String destination : destinations) {
					if(filename.equals(destination)) {
						found = true;
						break;
					}
				}
				// Skip if is an expected symlink
				if(!found) {
					PosixFile backmeup = new PosixFile(src, filename, false);
					backmeup.renameTo(
						findUnusedBackup(backmeup + backupSuffix, backupSeparator, backupExtension)
					);
					modified = true;
				}
			}
		}
		return modified;
	}

	/**
	 * Creates a directory, if needed.  If already exists makes sure it is a directory.
	 * Also sets or resets the ownership and permissions.
	 *
	 * @return  {@code true} if any modification was made
	 */
	public static boolean mkdir(String dirName, int mode, int uid, int gid) throws IOException {
		return mkdir(new PosixFile(dirName), mode, uid, gid, null);
	}

	/**
	 * Creates a directory, if needed.  If already exists makes sure it is a directory.
	 * Also sets or resets the ownership and permissions.
	 *
	 * @param  backupName  If exists but is not a directory, the existing is renamed to backupName
	 *
	 * @return  {@code true} if any modification was made
	 */
	public static boolean mkdir(String dirName, int mode, int uid, int gid, String backupName) throws IOException {
		return mkdir(new PosixFile(dirName), mode, uid, gid, (backupName == null) ? null : new PosixFile(backupName));
	}

	/**
	 * Creates a directory, if needed.  If already exists makes sure it is a directory.
	 * Also sets or resets the ownership and permissions.
	 *
	 * @return  {@code true} if any modification was made
	 */
	public static boolean mkdir(PosixFile uf, int mode, int uid, int gid) throws IOException {
		return mkdir(uf, mode, uid, gid, null);
	}

	/**
	 * Creates a directory, if needed.  If already exists makes sure it is a directory.
	 * Also sets or resets the ownership and permissions.
	 *
	 * @param  backup  If exists but is not a directory, the existing is renamed to backup
	 *
	 * @return  {@code true} if any modification was made
	 */
	public static boolean mkdir(PosixFile uf, int mode, int uid, int gid, PosixFile backup) throws IOException {
		boolean modified = false;
		Stat ufStat = uf.getStat();
		if(!ufStat.exists()) {
			uf.mkdir();
			ufStat = uf.getStat();
			modified = true;
		} else if(!ufStat.isDirectory()) {
			if(backup == null) {
				throw new IOException("File exists and is not a directory: " + uf.getPath());
			}
			uf.renameTo(backup);
			uf.mkdir();
			ufStat = uf.getStat();
			modified = true;
		}
		if(ufStat.getMode() != mode) {
			uf.setMode(mode);
			modified = true;
		}
		if(ufStat.getUid() != uid || ufStat.getGid() != gid) {
			uf.chown(uid, gid);
			modified = true;
		}
		return modified;
	}

	/**
	 * Creates an empty file, if needed.  If the file exists its contents are not altered.
	 * If already exists makes sure it is a file.
	 * Also sets or resets the ownership and permissions.
	 */
	public static void createEmptyFile(PosixFile uf, int mode, int uid, int gid) throws IOException {
		Stat ufStat = uf.getStat();
		if(!ufStat.exists()) {
			new FileOutputStream(uf.getFile()).close();
			ufStat = uf.getStat();
		} else if(!ufStat.isRegularFile()) throw new IOException("File exists and is not a regular file: " + uf.getPath());
		if(ufStat.getMode() != mode) uf.setMode(mode);
		if(ufStat.getUid() != uid || ufStat.getGid() != gid) uf.chown(uid, gid);
	}

	/**
	 * If the file starts with the provided prefix, strips that prefix from the
	 * file.  A new temp file is created and then renamed over the old.
	 */
	@SuppressWarnings("try")
	public static void stripFilePrefix(PosixFile uf, String prefix, int uid_min, int gid_min) throws IOException {
		// Remove the auto warning if the site has recently become manual
		int prefixLen=prefix.length();
		Stat ufStat = uf.getStat();
		if(ufStat.getSize()>=prefixLen) {
			try (InputStream in = new BufferedInputStream(uf.getSecureInputStream(uid_min, gid_min))) {
				StringBuilder sb = new StringBuilder(prefixLen);
				int ch;
				while(sb.length() < prefixLen && (ch = in.read()) != -1) {
					sb.append((char)ch);
				}
				if(sb.toString().equals(prefix)) {
					try (
						TempFileContext tempFileContext = new TempFileContext(uf.getFile().getParent());
						TempFile tempFile = tempFileContext.createTempFile(uf.getFile().getName())
					) {
						try (OutputStream out = new BufferedOutputStream(
							new PosixFile(tempFile.getFile()).getSecureOutputStream(
								ufStat.getUid(),
								ufStat.getGid(),
								ufStat.getMode(),
								true,
								uid_min,
								gid_min
							)
						)) {
							IoUtils.copy(in, out);
						}
						in.close(); // Close now before overwriting the file
						FileUtils.rename(tempFile.getFile(), uf.getFile());
					}
				}
			}
		}
	}

	/**
	 * Atomically replaces a file.  The file will always exist and will always
	 * be either the old of new version.
	 * <p>
	 * Will not overwrite the file if the contents already match,
	 * but the permissions and ownership will still be verified.
	 * </p>
	 * <p>
	 * New file contents, both for the file and its optional backup, are written
	 * to temp files and then atomically renamed into place, with the backup
	 * renamed into place first.
	 * </p>
	 *
	 * @param  file  the file to overwrite
	 * @param  backupFile  the optional backup file
	 * @param  restorecon  when not null, any file moved into place that might need "restorecon" will be added to the set
	 *
	 * @return {@code true} when the file is replaced with new content, has ownership updated, or has permissions updated
	 */
	public static boolean atomicWrite(PosixFile file, byte[] newContents, long mode, int uid, int gid, PosixFile backupFile, Set<PosixFile> restorecon) throws IOException {
		boolean updated;
		Stat fileStat = file.getStat();
		if(
			backupFile != null
			&& fileStat.exists()
			&& !fileStat.isRegularFile()
		) {
			file.renameTo(backupFile);
			fileStat = Stat.NOT_EXISTS;
			updated = true;
		}
		if(
			!fileStat.exists()
			// TODO: Find some way to avoid race condition and redirects while not doing funny file permission changes
			|| !file.contentEquals(newContents)
		) {
			try (TempFileContext tempFileContext = new TempFileContext(file.getFile().getParentFile())) {
				PosixFile backupTemp;
				if(backupFile != null && fileStat.exists()) {
					// Create temp backup
					backupTemp = new PosixFile(tempFileContext.createTempFile(backupFile.getFile().getName()).getFile());
					if(logger.isLoggable(Level.FINE)) logger.fine("mktemp \"" + backupFile + "\" -> \"" + backupTemp + '"');
					long numBytes = FileUtils.copy(file.getFile(), backupTemp.getFile());
					if(logger.isLoggable(Level.FINE)) logger.fine("cp \"" + file + "\" \"" + backupTemp + "\", " + numBytes + " bytes copied");
					if(fileStat.getSize() != numBytes) throw new IOException("File size mismatch: " + fileStat.getSize() + " != " + numBytes);
					Stat backupTempStat = backupTemp.getStat();
					if(backupTempStat.getSize() != numBytes) throw new IOException("File size mismatch: " + backupTempStat.getSize() + " != " + numBytes);
					// Set ownership
					if(backupTempStat.getUid() != uid || backupTempStat.getGid() != gid) {
						if(logger.isLoggable(Level.FINE)) logger.fine("chown " + uid + ':' + gid + " \"" + backupTemp + '"');
						backupTemp.chown(uid, gid);
					}
					// Set permissions
					if(backupTempStat.getMode() != mode) {
						if(logger.isLoggable(Level.FINE)) logger.fine("chmod " + Long.toOctalString(mode) + " \"" + backupTemp + '"');
						backupTemp.setMode(mode);
					}
					// Set modified time
					if(
						backupTempStat.getAccessTime() != fileStat.getAccessTime()
						|| backupTempStat.getModifyTime() != fileStat.getModifyTime()
					) {
						if(logger.isLoggable(Level.FINE)) logger.fine("utime " + fileStat.getAccessTime() + ' ' + fileStat.getModifyTime() + " \"" + backupTemp + '"');
						backupTemp.utime(
							fileStat.getAccessTime(),
							fileStat.getModifyTime()
						);
					}
				} else {
					// No backup
					backupTemp = null;
				}
				// Write the new contents into a temp file
				PosixFile fileTemp = new PosixFile(tempFileContext.createTempFile(file.getFile().getName()).getFile());
				if(logger.isLoggable(Level.FINE)) logger.fine("mktemp \"" + file + "\" -> \"" + fileTemp + '"');
				// TODO: Find some way to avoid race condition and redirects while not doing funny file permission changes
				try (FileOutputStream out = new FileOutputStream(fileTemp.getFile())) {
					out.write(newContents);
				}
				if(logger.isLoggable(Level.FINE)) logger.fine("Wrote " + newContents.length + " bytes to \"" + fileTemp + '"');
				Stat fileTempStat = fileTemp.getStat();
				if(fileTempStat.getSize() != newContents.length) throw new IOException("File size mismatch: " + fileTempStat.getSize() + " != " + newContents.length);
				// Set ownership
				if(fileTempStat.getUid() != uid || fileTempStat.getGid() != gid) {
					if(logger.isLoggable(Level.FINE)) logger.fine("chown " + uid + ':' + gid + " \"" + fileTemp + '"');
					fileTemp.chown(uid, gid);
				}
				// Set permissions
				if(fileTempStat.getMode() != mode) {
					if(logger.isLoggable(Level.FINE)) logger.fine("chmod " + Long.toOctalString(mode) + " \"" + fileTemp + '"');
					fileTemp.setMode(mode);
				}
				// Move backup into place
				if(backupTemp != null) {
					if(logger.isLoggable(Level.FINE)) logger.fine("mv \"" + backupTemp + "\" \"" + backupFile + '"');
					backupTemp.renameTo(backupFile);
					if(restorecon != null) restorecon.add(backupFile);
				}
				// Move file into place
				if(logger.isLoggable(Level.FINE)) logger.fine("mv \"" + fileTemp + "\" \"" + file + '"');
				fileTemp.renameTo(file);
			}
			if(restorecon != null) restorecon.add(file);
			updated = true;
		} else {
			updated = false;
			// Verify ownership
			if(fileStat.getUid() != uid || fileStat.getGid() != gid) {
				if(logger.isLoggable(Level.FINE)) logger.fine("chown " + uid + ':' + gid + " \"" + file + '"');
				file.chown(uid, gid);
				updated = true;
			}
			// Verify permissions
			if(fileStat.getMode() != mode) {
				if(logger.isLoggable(Level.FINE)) logger.fine("chmod " + Long.toOctalString(mode) + " \"" + file + '"');
				file.setMode(mode);
				updated = true;
			}
		}
		return updated;
	}

	/**
	 * Calls "restorecon" on the given set of paths if this server is CentOS 7
	 * and has selinux installed.
	 */
	public static void restorecon(Set<PosixFile> restorecon) throws IOException, SQLException {
		int size = restorecon.size();
		if(size > 0) {
			OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
			switch(osv.getPkey()) {
				case OperatingSystemVersion.CENTOS_5_DOM0_I686 :
				case OperatingSystemVersion.CENTOS_5_DOM0_X86_64 :
				case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
					// Nothing to do
					break;
				case OperatingSystemVersion.CENTOS_7_DOM0_X86_64 :
				case OperatingSystemVersion.CENTOS_7_X86_64 :
					String restoreconCommand = "/usr/sbin/restorecon";
					if(PackageManager.getInstalledPackage(PackageManager.PackageName.POLICYCOREUTILS) == null) {
						if(logger.isLoggable(Level.WARNING)) {
							logger.warning(PackageManager.PackageName.POLICYCOREUTILS + " package not installed, not running " + restoreconCommand + ": " + PackageManager.PackageName.POLICYCOREUTILS);
						}
					} else {
						String[] command = new String[2 + size];
						int i = 0;
						command[i++] = restoreconCommand;
						command[i++] = "-R";
						for(PosixFile uf : restorecon) {
							command[i++] = uf.getPath();
						}
						if(i != command.length) throw new ConcurrentModificationException();
						if(logger.isLoggable(Level.INFO)) {
							logger.info(AOServDaemon.getCommandString(command));
						}
						AOServDaemon.exec(command);
					}
					break;
				default :
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}
	}

	/**
	 * Finds an unused file, adding separator and "2" for second try, "3" for third, ...
	 */
	public static PosixFile findUnusedBackup(String prefix, String separator, String extension) throws IOException {
		int i = 0;
		while(++i > 0) {
			PosixFile uf = new PosixFile(
				(i == 1)
					? (prefix + extension)
					: (prefix + separator + i + extension)
			);
			if(!uf.getStat().exists()) {
				return uf;
			}
		}
		throw new IOException("All backup files between 1 and " + Integer.MAX_VALUE + " are used");
	}
}
