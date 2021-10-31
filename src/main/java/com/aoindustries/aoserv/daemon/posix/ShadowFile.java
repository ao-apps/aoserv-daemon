/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.posix;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.hodgepodge.util.Tuple2;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.Strings;
import com.aoapps.lang.math.SafeMath;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages access to the <code>/etc/shadow</code> file.
 *
 * @author  AO Industries, Inc.
 */
public final class ShadowFile {

	private static final Logger logger = Logger.getLogger(ShadowFile.class.getName());

	private static final PosixFile
		shadowFile       = new PosixFile("/etc/shadow"),
		backupShadowFile = new PosixFile("/etc/shadow-")
	;

	/**
	 * Represents one line of the <code>/etc/shadow</code> file on a POSIX server.
	 */
	public static final class Entry {

		/**
		 * @see  #getUsername()
		 */
		private final User.Name username;

		/**
		 * @see  #getPassword()
		 */
		private final String password;

		/**
		 * @see  #getChangedDate()
		 */
		private final int changedDate;

		/**
		 * @see  #getMinPasswordAge()
		 */
		private final Integer minPasswordAge;

		/**
		 * @see  #getMaxPasswordAge()
		 */
		private final Integer maxPasswordAge;

		/**
		 * @see  #getWarningDays()
		 */
		private final Integer warningDays;

		/**
		 * @see  #getInactivateDays()
		 */
		private final Integer inactivateDays;

		/**
		 * @see  #getExpirationDate()
		 */
		private final Integer expirationDate;

		/**
		 * @see  #getFlag()
		 */
		private final String flag;

		/**
		 * Constructs a shadow file entry given one line of the <code>/etc/shadow</code> file, not including
		 * the trailing newline (<code>'\n'</code>).  This may also be called providing only the username,
		 * in which case the default values are used and the password is set to {@link User#NO_PASSWORD_CONFIG_VALUE}
		 * (disabled).
		 */
		public Entry(String line) throws ValidationException {
			List<String> values = Strings.split(line, ':');
			int len = values.size();
			if(len < 1) throw new IllegalArgumentException("At least the first field of shadow file required: " + line);

			username = User.Name.valueOf(values.get(0));

			String s;

			if(len > 1 && (s = values.get(1)).length() > 0) password = s;
			else password = User.NO_PASSWORD_CONFIG_VALUE;

			if(len > 2 && (s = values.get(2)).length() > 0) changedDate = Integer.parseInt(s);
			else changedDate = getCurrentDate();

			if(len > 3 && (s = values.get(3)).length() > 0) minPasswordAge = Integer.parseInt(s);
			else minPasswordAge = null;

			if(len > 4 && (s = values.get(4)).length() > 0) maxPasswordAge = Integer.parseInt(s);
			else maxPasswordAge = null;

			if(len > 5 && (s = values.get(5)).length() > 0) warningDays = Integer.parseInt(s);
			else warningDays = null;

			if(len > 6 && (s = values.get(6)).length() > 0) inactivateDays = Integer.parseInt(s);
			else inactivateDays = null;

			if(len > 7 && (s = values.get(7)).length() > 0) expirationDate = Integer.parseInt(s);
			else expirationDate = null;

			if(len > 8 && (s = values.get(8)).length() > 0) flag = s;
			else flag = null;

			if(len > 9) throw new IllegalArgumentException("Too many fields: " + line);
		}

		/**
		 * Constructs a shadow file entry given all the values.
		 */
		public Entry(
			User.Name username,
			String password,
			int changedDate,
			Integer minPasswordAge,
			Integer maxPasswordAge,
			Integer warningDays,
			Integer inactivateDays,
			Integer expirationDate,
			String flag
		) {
			this.username = username;
			this.password = password;
			this.changedDate = changedDate;
			this.minPasswordAge = minPasswordAge;
			this.maxPasswordAge = maxPasswordAge;
			this.warningDays = warningDays;
			this.inactivateDays = inactivateDays;
			this.expirationDate = expirationDate;
			this.flag = flag;
		}

		/**
		 * Constructs a new shadow file entry for the given user and encrypted password.
		 */
		public Entry(User.Name username, String password, Integer newChangedDate) {
			this(
				username,
				password,
				newChangedDate != null ? newChangedDate : getCurrentDate(),
				0,
				99999,
				7,
				null,
				null,
				null
			);
		}

		/**
		 * Constructs a new shadow file entry for the given user.
		 */
		public Entry(User.Name username) {
			this(username, User.NO_PASSWORD_CONFIG_VALUE, null);
		}

		/**
		 * Gets the number of days from the Epoch for the current day.
		 */
		public static int getCurrentDate() {
			return getCurrentDate(System.currentTimeMillis());
		}

		/**
		 * Gets the number of days from the Epoch for the provided time in milliseconds from Epoch.
		 */
		public static int getCurrentDate(long time) {
			return SafeMath.castInt(time / (24 * 60 * 60 * 1000));
		}

		/**
		 * Gets this {@link Entry} as it would be written in <code>/etc/shadow</code>,
		 * not including any newline.
		 *
		 * @see  #appendTo(java.lang.Appendable)
		 */
		@Override
		public String toString() {
			try {
				return appendTo(new StringBuilder()).toString();
			} catch(IOException e) {
				throw new AssertionError(e);
			}
		}

		/**
		 * Appends this {@link Entry} as it would be written in <code>/etc/shadow</code>,
		 * not including any newline.
		 *
		 * @see  #toString()
		 */
		public <A extends Appendable> A appendTo(A out) throws IOException {
			out
				.append(username.toString())
				.append(':')
				.append(password)
				.append(':')
				.append(Integer.toString(changedDate))
				.append(':')
			;
			if(minPasswordAge != null) out.append(minPasswordAge.toString());
			out.append(':');
			if(maxPasswordAge != null) out.append(maxPasswordAge.toString());
			out.append(':');
			if(warningDays != null) out.append(warningDays.toString());
			out.append(':');
			if(inactivateDays != null) out.append(inactivateDays.toString());
			out.append(':');
			if(expirationDate != null) out.append(expirationDate.toString());
			out.append(':');
			if(flag != null) out.append(flag);
			return out;
		}

		/**
		 * The username the entry is for
		 */
		public User.Name getUsername() {
			return username;
		}

		/**
		 * The encrypted password
		 */
		public String getPassword() {
			return password;
		}

		/**
		 * Sets the encrypted password, optionally updating the changedDate.
		 *
		 * @param newChangedDate  The new changeDate or {@code null} to not alter
		 *
		 * @return  a new entry if the password changed or {@code this} when the password is the same
		 */
		public Entry setPassword(String newPassword, Integer newChangedDate) {
			if(newPassword.equals(password)) {
				return this;
			} else {
				return new Entry(
					username,
					newPassword,
					newChangedDate != null ? newChangedDate : this.changedDate,
					minPasswordAge,
					maxPasswordAge,
					warningDays,
					inactivateDays,
					expirationDate,
					flag
				);
			}
		}

		/**
		 * The days since Jan 1, 1970 password was last changed
		 */
		public int getChangedDate() {
			return changedDate;
		}

		/**
		 * The number of days until a password change is allowed
		 * or {@code null} if not set.
		 */
		public Integer getMinPasswordAge() {
			return minPasswordAge;
		}

		/**
		 * The number of days until a password change is forced
		 * or {@code null} if not set.
		 */
		public Integer getMaxPasswordAge() {
			return maxPasswordAge;
		}

		/**
		 * The days before password is to expire that user is warned of pending password expiration
		 * or {@code null} if not set.
		 */
		public Integer getWarningDays() {
			return warningDays;
		}

		/**
		 * The days after password expires that account is considered inactive and disabled
		 * or {@code null} if not set.
		 */
		public Integer getInactivateDays() {
			return inactivateDays;
		}

		/**
		 * The days since Jan 1, 1970 when account will be disabled
		 * or {@code null} if not set.
		 */
		public Integer getExpirationDate() {
			return expirationDate;
		}

		/**
		 * Reserved for future use,
		 * {@code null} if not set.
		 */
		public String getFlag() {
			return flag;
		}
	}

	/**
	 * Locks the shadow file for updates
	 */
	public static final Object shadowLock = new Object();

	/**
	 * Gets the encrypted password for one user on the system include the {@link Entry#getChangedDate() changeDate}, if known.
	 *
	 * If there is no entry for the user in the shadow file, returns <code>({@link User#NO_PASSWORD_CONFIG_VALUE}, null)</code>.
	 */
	public static Tuple2<String, Integer> getEncryptedPassword(User.Name username) throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		synchronized(shadowLock) {
			try {
				try (
					BufferedReader in = new BufferedReader(
						new InputStreamReader(
							new FileInputStream(shadowFile.getFile())
						)
					)
				) {
					String line;
					while((line = in.readLine()) != null) {
						Entry entry = new Entry(line);
						if(entry.getUsername().equals(username)) {
							return new Tuple2<>(entry.getPassword(), entry.getChangedDate());
						}
					}
				}
				return new Tuple2<>(User.NO_PASSWORD_CONFIG_VALUE, null);
			} catch(ValidationException e) {
				throw new IOException(e);
			}
		}
	}

	/**
	 * Reads the full contents of /etc/shadow
	 */
	private static Map<User.Name, Entry> readShadowFile() throws IOException {
		assert Thread.holdsLock(shadowLock);
		try {
			Map<User.Name, Entry> shadowEntries = new LinkedHashMap<>();
			try (
				BufferedReader in = new BufferedReader(
					new InputStreamReader(
						new FileInputStream(shadowFile.getFile())
					)
				)
			) {
				String line;
				while((line = in.readLine()) != null) {
					Entry entry = new Entry(line);
					if(shadowEntries.put(entry.getUsername(), entry) != null) {
						throw new IllegalStateException(shadowFile + " contains duplicate entry: " + line);
					}
				}
			}
			return shadowEntries;
		} catch(ValidationException e) {
			throw new IOException(e);
		}
	}

	private static byte[] createShadowFile(Iterable<Entry> shadowEntries) {
		try {
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			try (ChainWriter out = new ChainWriter(bout)) {
				boolean rootFound = false;
				for(Entry entry : shadowEntries) {
					if(entry.getUsername().equals(User.ROOT)) rootFound = true;
					entry.appendTo(out);
					out.print('\n');
				}
				if(!rootFound) throw new IllegalArgumentException(User.ROOT + " user not found while creating " + shadowFile);
			}
			return bout.toByteArray();
		} catch(IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Must hold {@link #shadowLock}
	 */
	public static void writeShadowFile(byte[] newContents, Set<PosixFile> restorecon) throws SQLException, IOException {
		assert Thread.holdsLock(shadowLock);
		// Determine permissions
		long mode;
		{
			OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
				// Set to 0400
				mode = 0400;
			} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
				// Set to 0000
				mode = 0000;
			} else {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}
		DaemonFileUtils.atomicWrite(
			shadowFile,
			newContents,
			mode,
			PosixFile.ROOT_UID,
			PosixFile.ROOT_GID,
			backupShadowFile,
			restorecon
		);
	}

	/**
	 * Builds a new version of the shadow file with necessary adjustments made.
	 *
	 * Must hold {@link #shadowLock}
	 */
	public static byte[] buildShadowFile(Set<User.Name> usernames) throws IOException {
		assert Thread.holdsLock(shadowLock);
		if(!usernames.contains(User.ROOT)) throw new IllegalArgumentException(User.ROOT + " user not found");
		Map<User.Name, Entry> shadowEntries = readShadowFile();
		// Remove any users that no longer exist
		Iterator<Map.Entry<User.Name, Entry>> entryIter = shadowEntries.entrySet().iterator();
		while(entryIter.hasNext()) {
			Map.Entry<User.Name, Entry> mapEntry = entryIter.next();
			User.Name username = mapEntry.getKey();
			if(!usernames.contains(username)) {
				if(logger.isLoggable(Level.INFO)) {
					logger.info("Removing user from " + shadowFile + ": " + username);
				}
				entryIter.remove();
			}
		}

		// Add new users
		for(User.Name username : usernames) {
			if(!shadowEntries.containsKey(username)) {
				if(logger.isLoggable(Level.INFO)) {
					logger.info("Adding user to " + shadowFile + ": " + username);
				}
				shadowEntries.put(username, new Entry(username));
			}
		}
		return createShadowFile(shadowEntries.values());
	}

	/**
	 * Sets the encrypted password for one user on the system.  This password must already
	 * be {@link PosixFile#crypt(java.lang.String, com.aoapps.io.posix.PosixFile.CryptAlgorithm) hashed}.
	 * <p>
	 * This method is synchronized with <code>doRebuild</code> to ensure that
	 * passwords are never lost during updates.
	 * </p>
	 *
	 * @param newChangedDate  The new changeDate or {@code null} to not alter
	 *
	 * @see PosixFile#crypt(java.lang.String, com.aoapps.io.posix.PosixFile.CryptAlgorithm)
	 */
	public static void setEncryptedPassword(User.Name username, String encryptedPassword, Integer newChangedDate) throws IOException, SQLException {
		synchronized(shadowLock) {
			Map<User.Name, Entry> shadowEntries = readShadowFile();

			Entry entry = shadowEntries.get(username);
			if(entry != null) {
				// Reset if already exists
				if(logger.isLoggable(Level.INFO)) {
					logger.info("Resetting password for existing entry in " + shadowFile + ": " + username);
				}
				shadowEntries.put(username, entry.setPassword(encryptedPassword, newChangedDate));
			} else {
				// Add if does not yet exist
				if(logger.isLoggable(Level.INFO)) {
					logger.info("Adding user to " + shadowFile + " for password reset: " + username);
				}
				shadowEntries.put(username, new Entry(username, encryptedPassword, newChangedDate));
			}

			Set<PosixFile> restorecon = new LinkedHashSet<>();
			writeShadowFile(
				createShadowFile(shadowEntries.values()),
				restorecon
			);
			DaemonFileUtils.restorecon(restorecon);
		}
	}

	/**
	 * Sets the password for one user on the system.
	 */
	public static void setPassword(User.Name username, String plaintext, PosixFile.CryptAlgorithm cryptAlgorithm, boolean updateChangedDate) throws IOException, SQLException {
		setEncryptedPassword(
			username,
			plaintext == null || plaintext.isEmpty()
				? User.NO_PASSWORD_CONFIG_VALUE
				: PosixFile.crypt(plaintext, cryptAlgorithm, AOServDaemon.getSecureRandom()),
			updateChangedDate ? Entry.getCurrentDate() : null
		);
	}

	private ShadowFile() {
	}
}
