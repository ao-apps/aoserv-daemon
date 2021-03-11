/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2017, 2018, 2020, 2021  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.unix;

import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.collections.AoCollections;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.lang.Strings;
import com.aoindustries.validation.ValidationException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages access to the <code>/etc/group</code> file.
 *
 * @author  AO Industries, Inc.
 */
final public class GroupFile {

	private static final Logger logger = Logger.getLogger(GroupFile.class.getName());

	private static final UnixFile
		groupFile       = new UnixFile("/etc/group"),
		backupGroupFile = new UnixFile("/etc/group-")
	;

	/**
	 * Represents one line of the <code>/etc/group</code> file on a POSIX server.
	 */
	final public static class Entry {

		/**
		 * @see  #getGroupName()
		 */
		private final Group.Name groupName;

		/**
		 * @see  #getGid()
		 */
		private final int gid;

		/**
		 * @see  #getGroupMembers()
		 */
		private final Set<User.Name> groupMembers;

		/**
		 * Constructs a group file entry given one line of the <code>/etc/group</code> file, not including
		 * the trailing newline (<code>'\n'</code>).
		 */
		public Entry(String line) throws ValidationException {
			List<String> values = Strings.split(line, ':');
			int len = values.size();
			if(len < 3) throw new IllegalArgumentException("At least the first three fields of group file required: " + line);

			groupName = Group.Name.valueOf(values.get(0));

			String S;

			if(len > 2 && (S = values.get(2)).length() > 0) gid = Integer.parseInt(S);
			else throw new IllegalArgumentException("gid missing: " + line);

			if(len > 3 && (S = values.get(3)).length() > 0) groupMembers = GShadowFile.Entry.parseUserIds(S);
			else groupMembers = Collections.emptySet();

			if(len > 4) throw new IllegalArgumentException("Too many fields: " + line);
		}

		/**
		 * Constructs a group file entry given all the values.
		 */
		public Entry(
			Group.Name groupName,
			int gid,
			Set<User.Name> groupMembers
		) {
			this.groupName = groupName;
			this.gid = gid;
			this.groupMembers = AoCollections.unmodifiableCopySet(groupMembers);
		}

		/**
		 * Gets this {@link Entry} as it would be written in <code>/etc/group</code>,
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
		 * Appends this {@link Entry} as it would be written in <code>/etc/group</code>,
		 * not including any newline.
		 *
		 * @see  #toString()
		 */
		public <A extends Appendable> A appendTo(A out) throws IOException {
			out
				.append(groupName.toString())
				.append(":x:")
				.append(Integer.toString(gid))
				.append(':');
			boolean didOne = false;
			for(User.Name groupMember : groupMembers) {
				if(didOne) out.append(',');
				else didOne = true;
				out.append(groupMember.toString());
			}
			return out;
		}

		/**
		 * The group name the entry is for
		 */
		public Group.Name getGroupName() {
			return groupName;
		}

		/**
		 * The group id.
		 */
		public int getGid() {
			return gid;
		}

		/**
		 * The unmodifiable set of group members
		 * or an empty set if not set.
		 */
		public Set<User.Name> getGroupMembers() {
			return groupMembers;
		}
	}

	/**
	 * Locks the group file for updates
	 */
	public static final Object groupLock = new Object();

	/**
	 * Reads the full contents of /etc/group
	 *
	 * Must hold {@link #groupLock}
	 */
	public static Map<Group.Name, Entry> readGroupFile() throws IOException {
		assert Thread.holdsLock(groupLock);
		try {
			Map<Group.Name, Entry> groupEntries = new LinkedHashMap<>();
			try (
				BufferedReader in = new BufferedReader(
					new InputStreamReader(
						new FileInputStream(groupFile.getFile())
					)
				)
			) {
				String line;
				while((line = in.readLine()) != null) {
					Entry entry = new Entry(line);
					if(groupEntries.put(entry.getGroupName(), entry) != null) {
						throw new IllegalStateException(groupFile + " contains duplicate entry: " + line);
					}
				}
			}
			return groupEntries;
		} catch(ValidationException e) {
			throw new IOException(e);
		}
	}

	private static byte[] createGroupFile(Iterable<Entry> groupEntries) {
		try {
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			try (ChainWriter out = new ChainWriter(bout)) {
				boolean rootFound = false;
				for(Entry entry : groupEntries) {
					if(entry.getGroupName().equals(Group.ROOT)) rootFound = true;
					entry.appendTo(out);
					out.print('\n');
				}
				if(!rootFound) throw new IllegalArgumentException(Group.ROOT + " group not found while creating " + groupFile);
			}
			return bout.toByteArray();
		} catch(IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Must hold {@link #groupLock}
	 */
	public static void writeGroupFile(byte[] newContents, Set<UnixFile> restorecon) throws SQLException, IOException {
		assert Thread.holdsLock(groupLock);
		DaemonFileUtils.atomicWrite(
			groupFile,
			newContents,
			0644,
			UnixFile.ROOT_UID,
			UnixFile.ROOT_GID,
			backupGroupFile,
			restorecon
		);
	}

	/**
	 * Builds a new version of the group file with necessary adjustments made.
	 *
	 * Must hold {@link #groupLock}
	 */
	public static byte[] buildGroupFile(Map<Group.Name, Entry> groups, int gidMin, int gidMax) throws IOException {
		assert Thread.holdsLock(groupLock);
		if(!groups.containsKey(Group.ROOT)) throw new IllegalArgumentException(Group.ROOT + " group not found");
		Map<Group.Name, Entry> groupEntries = readGroupFile();
		// Remove any groups that no longer exist and verify group members
		Iterator<Map.Entry<Group.Name, Entry>> entryIter = groupEntries.entrySet().iterator();
		while(entryIter.hasNext()) {
			Map.Entry<Group.Name, Entry> mapEntry = entryIter.next();
			Group.Name groupName = mapEntry.getKey();
			Entry existingEntry = mapEntry.getValue();
			boolean existingIsSystem = existingEntry.gid < gidMin || existingEntry.gid > gidMax;
			if(groups.containsKey(groupName)) {
				Entry expectedEntry = groups.get(groupName);
				boolean expectedIsSystem = expectedEntry.gid < gidMin || expectedEntry.gid > gidMax;
				if(existingEntry.gid != expectedEntry.gid) {
					if(existingIsSystem != expectedIsSystem) {
						throw new IllegalArgumentException("Refusing to change group id between system and regular users from " + existingEntry.gid + " to " + expectedEntry.gid + " for " + groupName);
					}
					if(existingIsSystem) {
						throw new IllegalArgumentException("Refusing to change system group id from " + existingEntry.gid + " to " + expectedEntry.gid + " for " + groupName);
					}
				}
				// Verify group members match
				Set<User.Name> expectedMembers = expectedEntry.groupMembers;
				if(
					existingEntry.gid != expectedEntry.gid
					|| !existingEntry.getGroupMembers().equals(expectedMembers)
				) {
					assert existingEntry.groupName.equals(groupName);
					if(logger.isLoggable(Level.INFO)) {
						if(existingEntry.gid != expectedEntry.gid) {
							logger.info("Updating gid in " + groupFile + ": " + groupName + " -> " + expectedEntry.gid);
						}
						if(!existingEntry.getGroupMembers().equals(expectedMembers)) {
							logger.info("Updating group members in " + groupFile + ": " + groupName + " -> " + expectedMembers);
						}
					}
					mapEntry.setValue(expectedEntry);
				}
			} else {
				if(existingIsSystem) {
					throw new IllegalArgumentException("Refusing to remove system group from " + groupFile + " for " + groupName);
				}
				if(logger.isLoggable(Level.INFO)) {
					logger.info("Removing group from " + groupFile + ": " + groupName);
				}
				entryIter.remove();
			}
		}

		// Add new groups
		for(Entry entry : groups.values()) {
			Group.Name groupName = entry.groupName;
			if(!groupEntries.containsKey(groupName)) {
				if(logger.isLoggable(Level.INFO)) {
					logger.info("Adding group to " + groupFile + ": " + groupName);
				}
				groupEntries.put(groupName, entry);
			}
		}
		return createGroupFile(groupEntries.values());
	}

	private GroupFile() {
	}
}
