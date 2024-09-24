/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2017, 2018, 2019, 2020, 2021, 2022, 2023, 2024  AO Industries, Inc.
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

import com.aoapps.collections.AoCollections;
import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.Strings;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages access to the <code>/etc/gshadow</code> file.
 *
 * @author  AO Industries, Inc.
 */
public final class GshadowFile {

  /** Make no instances. */
  private GshadowFile() {
    throw new AssertionError();
  }

  private static final Logger logger = Logger.getLogger(GshadowFile.class.getName());

  private static final PosixFile gshadowFile = new PosixFile("/etc/gshadow");
  private static final PosixFile backupGShadowFile = new PosixFile("/etc/gshadow-");

  /**
   * Represents one line of the <code>/etc/gshadow</code> file on a POSIX server.
   */
  public static final class Entry {

    /**
     * @see  #getGroupName()
     */
    private final Group.Name groupName;

    /**
     * @see  #getPassword()
     */
    private final String password;

    /**
     * @see  #getGroupAdministrators()
     */
    private final Set<User.Name> groupAdministrators;

    /**
     * @see  #getGroupMembers()
     */
    private final Set<User.Name> groupMembers;

    static Set<User.Name> parseUserIds(String s) throws ValidationException {
      List<String> usernames = Strings.splitCommaSpace(s);
      int size = usernames.size();
      if (size == 0) {
        return Collections.emptySet();
      }
      if (size == 1) {
        return Collections.singleton(User.Name.valueOf(usernames.get(0)));
      }
      Set<User.Name> userIds = AoCollections.newLinkedHashSet(size);
      for (String username : usernames) {
        User.Name userId = User.Name.valueOf(username);
        if (!userIds.add(userId)) {
          throw new IllegalStateException("Duplicate userId: " + userId);
        }
      }
      return Collections.unmodifiableSet(userIds);
    }

    /**
     * Constructs a gshadow file entry given one line of the <code>/etc/gshadow</code> file, not including
     * the trailing newline (<code>'\n'</code>).
     */
    private Entry(String line) throws ValidationException {
      List<String> values = Strings.split(line, ':');
      int len = values.size();
      if (len < 1) {
        throw new IllegalArgumentException("At least the first field of gshadow file required: " + line);
      }

      groupName = Group.Name.valueOf(values.get(0));

      String s;

      if (len > 1 && (s = values.get(1)).length() > 0) {
        password = s;
      } else {
        password = null;
      }

      if (len > 2 && (s = values.get(2)).length() > 0) {
        groupAdministrators = parseUserIds(s);
      } else {
        groupAdministrators = Collections.emptySet();
      }

      if (len > 3 && (s = values.get(3)).length() > 0) {
        groupMembers = parseUserIds(s);
      } else {
        groupMembers = Collections.emptySet();
      }

      if (len > 4) {
        throw new IllegalArgumentException("Too many fields: " + line);
      }

      checkNoUserIdOverlap();
    }

    /**
     * Constructs a gshadow file entry given all the values.
     */
    public Entry(
        Group.Name groupName,
        String password,
        Set<User.Name> groupAdministrators,
        Set<User.Name> groupMembers
    ) {
      this.groupName = groupName;
      this.password = password;
      this.groupAdministrators = AoCollections.unmodifiableCopySet(groupAdministrators);
      this.groupMembers = AoCollections.unmodifiableCopySet(groupMembers);
      checkNoUserIdOverlap();
    }

    private void checkNoUserIdOverlap() throws IllegalArgumentException {
      Set<User.Name> intersection = new LinkedHashSet<>(groupAdministrators);
      intersection.retainAll(groupMembers);
      if (!intersection.isEmpty()) {
        throw new IllegalArgumentException("Users listed as both administrator and regular member: " + intersection);
      }
    }

    /**
     * Gets this {@link Entry} as it would be written in <code>/etc/gshadow</code>,
     * not including any newline.
     *
     * @see  #appendTo(java.lang.Appendable)
     */
    @Override
    public String toString() {
      try {
        return appendTo(new StringBuilder()).toString();
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    /**
     * Appends this {@link Entry} as it would be written in <code>/etc/gshadow</code>,
     * not including any newline.
     *
     * @see  #toString()
     */
    public <A extends Appendable> A appendTo(A out) throws IOException {
      out.append(groupName.toString()).append(':');
      if (password != null) {
        out.append(password);
      }
      out.append(':');
      boolean didOne = false;
      for (User.Name groupAdministrator : groupAdministrators) {
        if (didOne) {
          out.append(',');
        } else {
          didOne = true;
        }
        out.append(groupAdministrator.toString());
      }
      out.append(':');
      didOne = false;
      for (User.Name groupMember : groupMembers) {
        if (didOne) {
          out.append(',');
        } else {
          didOne = true;
        }
        out.append(groupMember.toString());
      }
      return out;
    }

    /**
     * The group name the entry is for.
     */
    public Group.Name getGroupName() {
      return groupName;
    }

    /**
     * The encrypted password
     * or {@code null} if not set.
     */
    public String getPassword() {
      return password;
    }

    /**
     * The unmodifiable set of group administrators
     * or an empty set if not set.
     */
    @SuppressWarnings("ReturnOfCollectionOrArrayField") // Returning unmodifiable
    public Set<User.Name> getGroupAdministrators() {
      return groupAdministrators;
    }

    /**
     * The unmodifiable set of non-administrative group members
     * or an empty set if not set.
     */
    @SuppressWarnings("ReturnOfCollectionOrArrayField") // Returning unmodifiable
    public Set<User.Name> getGroupMembers() {
      return groupMembers;
    }
  }

  /**
   * Locks the gshadow file for updates.
   */
  public static final Object gshadowLock = new Object();

  /**
   * Reads the full contents of <code>/etc/gshadow</code>.
   */
  private static Map<Group.Name, Entry> readGshadowFile() throws IOException {
    assert Thread.holdsLock(gshadowLock);
    try {
      Map<Group.Name, Entry> gshadowEntries = new LinkedHashMap<>();
      try (
          BufferedReader in = new BufferedReader(
              new InputStreamReader(
                  new FileInputStream(gshadowFile.getFile())
              )
          )
          ) {
        String line;
        while ((line = in.readLine()) != null) {
          Entry entry = new Entry(line);
          if (gshadowEntries.put(entry.getGroupName(), entry) != null) {
            throw new IllegalStateException(gshadowFile + " contains duplicate entry: " + line);
          }
        }
      }
      return gshadowEntries;
    } catch (ValidationException e) {
      throw new IOException(e);
    }
  }

  private static byte[] createGshadowFile(Iterable<Entry> gshadowEntries) {
    try {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      try (ChainWriter out = new ChainWriter(bout)) {
        boolean rootFound = false;
        for (Entry entry : gshadowEntries) {
          if (entry.getGroupName().equals(Group.ROOT)) {
            rootFound = true;
          }
          entry.appendTo(out);
          out.print('\n');
        }
        if (!rootFound) {
          throw new IllegalArgumentException(Group.ROOT + " group not found while creating " + gshadowFile);
        }
      }
      return bout.toByteArray();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Must hold {@link #gshadowLock}.
   */
  public static void writeGshadowFile(byte[] newContents, Set<PosixFile> restorecon) throws SQLException, IOException {
    assert Thread.holdsLock(gshadowLock);
    // Determine permissions
    long mode;
      {
        OperatingSystemVersion osv = AoservDaemon.getThisServer().getHost().getOperatingSystemVersion();
        int osvId = osv.getPkey();
        if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
          // Set to 0400
          mode = 0400;
        } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64
            || osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
          // Set to 0000
          mode = 0000;
        } else {
          throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    DaemonFileUtils.atomicWrite(
        gshadowFile,
        newContents,
        mode,
        PosixFile.ROOT_UID,
        PosixFile.ROOT_GID,
        backupGShadowFile,
        restorecon
    );
  }

  /**
   * Builds a new version of the gshadow file with necessary adjustments made.
   * <p>
   * Must hold {@link #gshadowLock}.
   * </p>
   */
  public static byte[] buildGshadowFile(Map<Group.Name, Set<User.Name>> groups) throws IOException {
    assert Thread.holdsLock(gshadowLock);
    if (!groups.containsKey(Group.ROOT)) {
      throw new IllegalArgumentException(Group.ROOT + " group not found");
    }
    Map<Group.Name, Entry> gshadowEntries = readGshadowFile();
    // Remove any groups that no longer exist and verify group members
    Iterator<Map.Entry<Group.Name, Entry>> entryIter = gshadowEntries.entrySet().iterator();
    while (entryIter.hasNext()) {
      Map.Entry<Group.Name, Entry> mapEntry = entryIter.next();
      Group.Name groupName = mapEntry.getKey();
      if (groups.containsKey(groupName)) {
        // Verify group members match
        Set<User.Name> expectedMembers = groups.get(groupName);
        Entry entry = mapEntry.getValue();
        if (!entry.getGroupMembers().equals(expectedMembers)) {
          assert entry.groupName.equals(groupName);
          if (logger.isLoggable(Level.INFO)) {
            logger.info("Updating group members in " + gshadowFile + ": " + groupName + " → " + expectedMembers);
          }
          mapEntry.setValue(
              new Entry(
                  groupName,
                  entry.password,
                  entry.groupAdministrators,
                  expectedMembers
              )
          );
        }
      } else {
        if (logger.isLoggable(Level.INFO)) {
          logger.info("Removing group from " + gshadowFile + ": " + groupName);
        }
        entryIter.remove();
      }
    }

    // Add new groups
    for (Map.Entry<Group.Name, Set<User.Name>> entry : groups.entrySet()) {
      Group.Name groupName = entry.getKey();
      if (!gshadowEntries.containsKey(groupName)) {
        if (logger.isLoggable(Level.INFO)) {
          logger.info("Adding group to " + gshadowFile + ": " + groupName);
        }
        gshadowEntries.put(
            groupName,
            new Entry(
                groupName,
                null,
                Collections.emptySet(),
                entry.getValue()
            )
        );
      }
    }
    return createGshadowFile(gshadowEntries.values());
  }
}
