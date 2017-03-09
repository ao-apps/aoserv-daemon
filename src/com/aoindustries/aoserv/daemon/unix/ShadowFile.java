/*
 * Copyright 2001-2013, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.unix;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <code>ShadowFileEntry</code> represents one line of the
 * <code>/etc/shadow</code> file on a Unix server.
 *
 * @author  AO Industries, Inc.
 */
final public class ShadowFile {

	public static final UnixFile
		newShadowFile=new UnixFile("/etc/shadow.new"),
		shadowFile=new UnixFile("/etc/shadow"),
		backupShadowFile=new UnixFile("/etc/shadow.old")
	;

	/** Locks the shadow file for updates */
	private static final Object	shadowLock=new Object();

	private ShadowFile(/*String line*/) {
	}

	/**
	 * Gets the encypted password for one user on the system.
	 * <p>
	 * This method is synchronized with <code>doRebuild</code> to ensure that
	 * passwords are never lost during updates.
	 */
	public static String getEncryptedPassword(String username) throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
			&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		synchronized(shadowLock) {
			BufferedReader in = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(shadowFile.getFile()))));
			String password;
			try {
				password=LinuxAccount.NO_PASSWORD_CONFIG_VALUE;
				String line;
				while ((line = in.readLine()) != null) {
					ShadowFileEntry entry = new ShadowFileEntry(line);
					if(entry.username.equals(username)) {
						password=entry.password;
						break;
					}
				}
			} finally {
				in.close();
			}

			return password;
		}
	}

	public static void rebuildShadowFile(List<LinuxServerAccount> accounts) throws IOException, SQLException {
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
			&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		int uid_min = thisAoServer.getUidMin().getID();
		int gid_min = thisAoServer.getGidMin().getID();

		synchronized(shadowLock) {
			/*
			 * Get the old data from /etc/shadow
			 */
			Map<String,ShadowFileEntry> shadowEntries = new HashMap<>();
			try (BufferedReader in = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(shadowFile.getFile()))))) {
				String line;
				while ((line = in.readLine()) != null) {
					ShadowFileEntry entry = new ShadowFileEntry(line);
					if (shadowEntries.containsKey(entry.username)) throw new IllegalArgumentException("Shadow file contains duplicate entry: " + line);
					shadowEntries.put(entry.username, entry);
				}
			}

			/*
			 * Write the new /etc/shadow file, creating default entries if needed.
			 */
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					newShadowFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true, uid_min, gid_min)
				)
			);
			try {
				boolean rootFound=false;
				for (int c = 0; c < accounts.size(); c++) {
					String username = accounts.get(c)
						.getLinuxAccount()
						.getUsername()
						.getUsername()
					;
					if(username.equals(LinuxAccount.ROOT)) {
						ShadowFileEntry entry = shadowEntries.get(username);
						if (entry == null) entry = new ShadowFileEntry(username);
						out.print(entry.toString());
						out.print('\n');
						rootFound=true;
						break;
					}
				}
				if(!rootFound) throw new SQLException("root user not found while creating "+newShadowFile.getPath());
				for (int c = 0; c < accounts.size(); c++) {
					String username = accounts.get(c)
						.getLinuxAccount()
						.getUsername()
						.getUsername()
					;
					if(!username.equals(LinuxAccount.ROOT)) {
						ShadowFileEntry entry = shadowEntries.get(username);
						if (entry == null) entry = new ShadowFileEntry(username);
						out.print(entry.toString());
						out.print('\n');
					}
				}
			} finally {
				out.flush();
				out.close();
			}

			if(newShadowFile.getStat().getSize()>0) {
				if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
					// Do nothing
				} else if(osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64) {
					// Do nothing
				} else if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
					newShadowFile.setMode(0400);
				} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				shadowFile.renameTo(backupShadowFile);
				newShadowFile.renameTo(shadowFile);
			} else throw new IOException(newShadowFile.getPath()+" has zero or unknown length");
		}
	}

	/**
	 * Sets the encypted password for one user on the system.  This password must already
	 * be hashed using the crypt or MD5 algorithm.
	 * <p>
	 * This method is synchronized with <code>doRebuild</code> to ensure that
	 * passwords are never lost during updates.
	 */
	public static void setEncryptedPassword(String username, String encryptedPassword) throws IOException, SQLException {
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
			&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		int uid_min = thisAoServer.getUidMin().getID();
		int gid_min = thisAoServer.getGidMin().getID();

		synchronized(shadowLock) {
			/*
			 * Get the old data from /etc/shadow
			 */
			List<ShadowFileEntry> shadowEntries = new ArrayList<>();
			boolean userFound=false;
			try (BufferedReader in = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(shadowFile.getFile()))))) {
				// Reset if already exists
				String line;
				while ((line = in.readLine()) != null) {
					ShadowFileEntry entry = new ShadowFileEntry(line);
					if(entry.username.equals(username)) {
						userFound=true;
						entry.password=encryptedPassword;
					}
					shadowEntries.add(entry);
				}
			}

			// Add if does not yet exist
			if(!userFound) shadowEntries.add(new ShadowFileEntry(username+':'+encryptedPassword));

			/*
			 * Write the new /etc/shadow file.
			 */
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					newShadowFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true, uid_min, gid_min)
				)
			);
			try {
				for (ShadowFileEntry entry : shadowEntries) {
					out.print(entry.toString());
					out.print('\n');
				}
			} finally {
				out.flush();
				out.close();
			}

			/*
			 * Move the new file into place.
			 */
			shadowFile.renameTo(backupShadowFile);
			newShadowFile.renameTo(shadowFile);
		}
	}

	/**
	 * Sets the password for one user on the system.
	 */
	public static void setPassword(String username, String plaintext) throws IOException, SQLException {
		setEncryptedPassword(
			username,
			plaintext==null || plaintext.length()==0
			?LinuxAccount.NO_PASSWORD_CONFIG_VALUE
			:UnixFile.crypt(plaintext, AOServDaemon.getRandom())
		);
	}
}
