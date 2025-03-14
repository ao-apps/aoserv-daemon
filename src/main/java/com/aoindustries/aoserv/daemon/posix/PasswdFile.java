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

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.Strings;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.User.Gecos;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages access to the <code>/etc/passwd</code> file.
 *
 * @author  AO Industries, Inc.
 */
public final class PasswdFile {

  /** Make no instances. */
  private PasswdFile() {
    throw new AssertionError();
  }

  private static final Logger logger = Logger.getLogger(PasswdFile.class.getName());

  private static final PosixFile passwdFile = new PosixFile("/etc/passwd");
  private static final PosixFile backupPasswdFile = new PosixFile("/etc/passwd-");

  /**
   * Represents one line of the <code>/etc/passwd</code> file on a POSIX server.
   */
  public static final class Entry {

    /**
     * @see  #getUsername()
     */
    private final User.Name username;

    /**
     * @see  #getUid()
     */
    private final int uid;

    /**
     * @see  #getGid()
     */
    private final int gid;

    /**
     * @see  #getFullName()
     */
    private final Gecos fullName;

    /**
     * @see  #getOfficeLocation()
     */
    private final Gecos officeLocation;

    /**
     * @see  #getOfficePhone()
     */
    private final Gecos officePhone;

    /**
     * @see  #getHomePhone()
     */
    private final Gecos homePhone;

    /**
     * @see  #getHome()
     */
    private final PosixPath home;

    /**
     * @see  #getShell()
     */
    private final PosixPath shell;

    /**
     * Constructs a passwd file entry given one line of the <code>/etc/passwd</code> file, not including
     * the trailing newline (<code>'\n'</code>).
     */
    private Entry(String line) throws ValidationException {
      List<String> values = Strings.split(line, ':');
      int len = values.size();
      if (len < 7) {
        throw new IllegalArgumentException("At least the first seven fields of passwd file required: " + line);
      }

      username = User.Name.valueOf(values.get(0));

      String s;

      if (len > 2 && (s = values.get(2)).length() > 0) {
        uid = Integer.parseInt(s);
      } else {
        throw new IllegalArgumentException("uid missing: " + line);
      }

      if (len > 3 && (s = values.get(3)).length() > 0) {
        gid = Integer.parseInt(s);
      } else {
        throw new IllegalArgumentException("gid missing: " + line);
      }

      List<String> fields;
      if (len > 4 && (s = values.get(4)).length() > 0) {
        fields = Strings.split(s, ',');
      } else {
        fields = Collections.emptyList();
      }
      int fieldsLen = fields.size();

      if (fieldsLen > 0 && (s = fields.get(0)).length() > 0) {
        fullName = Gecos.valueOf(s);
      } else {
        fullName = null;
      }

      if (fieldsLen > 1 && (s = fields.get(1)).length() > 0) {
        officeLocation = Gecos.valueOf(s);
      } else {
        officeLocation = null;
      }

      if (fieldsLen > 2 && (s = fields.get(2)).length() > 0) {
        officePhone = Gecos.valueOf(s);
      } else {
        officePhone = null;
      }

      if (fieldsLen > 3 && (s = fields.get(3)).length() > 0) {
        homePhone = Gecos.valueOf(s);
      } else {
        homePhone = null;
      }

      if (fields.size() > 4) {
        throw new IllegalArgumentException("Too many GECOS fields: " + line);
      }

      if (len > 5 && (s = values.get(5)).length() > 0) {
        home = PosixPath.valueOf(s);
      } else {
        throw new IllegalArgumentException("home missing: " + line);
      }

      if (len > 6 && (s = values.get(6)).length() > 0) {
        shell = PosixPath.valueOf(s);
      } else {
        throw new IllegalArgumentException("shell missing: " + line);
      }

      if (len > 7) {
        throw new IllegalArgumentException("Too many fields: " + line);
      }
    }

    /**
     * Constructs a passwd file entry given all the values.
     */
    public Entry(
        User.Name username,
        int uid,
        int gid,
        Gecos fullName,
        Gecos officeLocation,
        Gecos officePhone,
        Gecos homePhone,
        PosixPath home,
        PosixPath shell
    ) {
      this.username = username;
      this.uid = uid;
      this.gid = gid;
      this.fullName = fullName;
      this.officeLocation = officeLocation;
      this.officePhone = officePhone;
      this.homePhone = homePhone;
      this.home = home;
      this.shell = shell;
    }

    /**
     * Gets this {@link Entry} as it would be written in <code>/etc/passwd</code>,
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
     * Appends this {@link Entry} as it would be written in <code>/etc/passwd</code>,
     * not including any newline.
     *
     * @see  #toString()
     */
    public <A extends Appendable> A appendTo(A out) throws IOException {
      out
          .append(username.toString())
          .append(":x:")
          .append(Integer.toString(uid))
          .append(':')
          .append(Integer.toString(gid))
          .append(':');
      int commaCount = 0;
      if (fullName != null) {
        out.append(fullName.toString());
      }
      if (officeLocation != null) {
        out.append(',');
        commaCount++;
        out.append(officeLocation.toString());
      }
      if (officePhone != null) {
        while (commaCount < 2) {
          out.append(',');
          commaCount++;
        }
        out.append(officePhone.toString());
      }
      if (homePhone != null) {
        while (commaCount < 3) {
          out.append(',');
          commaCount++;
        }
        out.append(homePhone.toString());
      }
      out
          .append(':')
          .append(home.toString())
          .append(':')
          .append(shell.toString());
      return out;
    }

    /**
     * The username the entry is for.
     */
    public User.Name getUsername() {
      return username;
    }

    /**
     * The user id.
     */
    public int getUid() {
      return uid;
    }

    /**
     * The group id.
     */
    public int getGid() {
      return gid;
    }

    /**
     * The full name or {@code null} if not set.
     */
    public Gecos getFullName() {
      return fullName;
    }

    /**
     * The office location or {@code null} if not set.
     */
    public Gecos getOfficeLocation() {
      return officeLocation;
    }

    /**
     * The office phone or {@code null} if not set.
     */
    public Gecos getOfficePhone() {
      return officePhone;
    }

    /**
     * The home phone or {@code null} if not set.
     */
    public Gecos getHomePhone() {
      return homePhone;
    }

    /**
     * The home directory.
     */
    public PosixPath getHome() {
      return home;
    }

    /**
     * The shell interpreter.
     */
    public PosixPath getShell() {
      return shell;
    }
  }

  /**
   * Locks the passwd file for updates.
   */
  public static final Object passwdLock = new Object();

  /**
   * Reads the full contents of <code>/etc/passwd</code>.
   *
   * <p>Must hold {@link #passwdLock}.</p>
   */
  public static Map<User.Name, Entry> readPasswdFile() throws IOException {
    assert Thread.holdsLock(passwdLock);
    try {
      Map<User.Name, Entry> passwdEntries = new LinkedHashMap<>();
      try (
          BufferedReader in = new BufferedReader(
              new InputStreamReader(
                  new FileInputStream(passwdFile.getFile())
              )
          )
          ) {
        String line;
        while ((line = in.readLine()) != null) {
          Entry entry = new Entry(line);
          if (passwdEntries.put(entry.getUsername(), entry) != null) {
            throw new IllegalStateException(passwdFile + " contains duplicate entry: " + line);
          }
        }
      }
      return passwdEntries;
    } catch (ValidationException e) {
      throw new IOException(e);
    }
  }

  private static byte[] createPasswdFile(Iterable<Entry> passwdEntries) {
    try {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      try (ChainWriter out = new ChainWriter(bout)) {
        boolean rootFound = false;
        for (Entry entry : passwdEntries) {
          if (entry.getUsername().equals(User.ROOT)) {
            rootFound = true;
          }
          entry.appendTo(out);
          out.print('\n');
        }
        if (!rootFound) {
          throw new IllegalArgumentException(User.ROOT + " user not found while creating " + passwdFile);
        }
      }
      return bout.toByteArray();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Must hold {@link #passwdLock}.
   */
  public static void writePasswdFile(byte[] newContents, Set<PosixFile> restorecon) throws IOException {
    assert Thread.holdsLock(passwdLock);
    DaemonFileUtils.atomicWrite(
        passwdFile,
        newContents,
        0644,
        PosixFile.ROOT_UID,
        PosixFile.ROOT_GID,
        backupPasswdFile,
        restorecon
    );
  }

  /**
   * Builds a new version of the passwd file with necessary adjustments made.
   *
   * <p>Must hold {@link #passwdLock}.</p>
   */
  public static byte[] buildPasswdFile(Map<User.Name, Entry> expectedEntries, int uidMin, int uidMax) throws IOException {
    assert Thread.holdsLock(passwdLock);
    if (!expectedEntries.containsKey(User.ROOT)) {
      throw new IllegalArgumentException(User.ROOT + " user not found");
    }
    Map<User.Name, Entry> passwdEntries = readPasswdFile();
    // Remove any users that no longer exist and verify fields match
    Iterator<Map.Entry<User.Name, Entry>> entryIter = passwdEntries.entrySet().iterator();
    while (entryIter.hasNext()) {
      Map.Entry<User.Name, Entry> mapEntry = entryIter.next();
      User.Name username = mapEntry.getKey();
      Entry existingEntry = mapEntry.getValue();
      boolean existingIsSystem = existingEntry.uid < uidMin || existingEntry.uid > uidMax;
      if (expectedEntries.containsKey(username)) {
        Entry expectedEntry = expectedEntries.get(username);
        boolean expectedIsSystem = expectedEntry.uid < uidMin || expectedEntry.uid > uidMax;
        if (existingEntry.uid != expectedEntry.uid) {
          if (existingIsSystem != expectedIsSystem) {
            throw new IllegalArgumentException("Refusing to change user id between system and regular users from " + existingEntry.uid + " to " + expectedEntry.uid + " for " + username);
          }
          if (existingIsSystem) {
            throw new IllegalArgumentException("Refusing to change system user id from " + existingEntry.uid + " to " + expectedEntry.uid + " for " + username);
          }
        }
        if (existingEntry.gid != expectedEntry.gid) {
          if (existingIsSystem) {
            throw new IllegalArgumentException("Refusing to change system group id from " + existingEntry.gid + " to " + expectedEntry.gid + " for " + username);
          }
        }

        // Verify other fields match
        if (
            existingEntry.uid != expectedEntry.uid
                || existingEntry.gid != expectedEntry.gid
                || !Objects.equals(existingEntry.fullName, expectedEntry.fullName)
                || !Objects.equals(existingEntry.officeLocation, expectedEntry.officeLocation)
                || !Objects.equals(existingEntry.officePhone, expectedEntry.officePhone)
                || !Objects.equals(existingEntry.homePhone, expectedEntry.homePhone)
                || !existingEntry.home.equals(expectedEntry.home)
                || !existingEntry.shell.equals(expectedEntry.shell)
        ) {
          assert existingEntry.username.equals(username);
          if (logger.isLoggable(Level.INFO)) {
            if (existingEntry.uid != expectedEntry.uid) {
              logger.info("Updating uid in " + passwdFile + ": " + username + " → " + expectedEntry.uid);
            }
            if (existingEntry.gid != expectedEntry.gid) {
              logger.info("Updating gid in " + passwdFile + ": " + username + " → " + expectedEntry.gid);
            }
            if (!Objects.equals(existingEntry.fullName, expectedEntry.fullName)) {
              logger.info("Updating full name in " + passwdFile + ": " + username + " → " + expectedEntry.fullName);
            }
            if (!Objects.equals(existingEntry.officeLocation, expectedEntry.officeLocation)) {
              logger.info("Updating office location in " + passwdFile + ": " + username + " → " + expectedEntry.officeLocation);
            }
            if (!Objects.equals(existingEntry.officePhone, expectedEntry.officePhone)) {
              logger.info("Updating office phone in " + passwdFile + ": " + username + " → " + expectedEntry.officePhone);
            }
            if (!Objects.equals(existingEntry.homePhone, expectedEntry.homePhone)) {
              logger.info("Updating home phone in " + passwdFile + ": " + username + " → " + expectedEntry.homePhone);
            }
            if (!existingEntry.home.equals(expectedEntry.home)) {
              logger.info("Updating home directory in " + passwdFile + ": " + username + " → " + expectedEntry.home);
            }
            if (!existingEntry.shell.equals(expectedEntry.shell)) {
              logger.info("Updating shell in " + passwdFile + ": " + username + " → " + expectedEntry.shell);
            }
          }
          mapEntry.setValue(expectedEntry);
        }
      } else {
        if (existingIsSystem) {
          throw new IllegalArgumentException("Refusing to remove system user from " + passwdFile + " for " + username);
        }
        if (logger.isLoggable(Level.INFO)) {
          logger.info("Removing user from " + passwdFile + ": " + username);
        }
        entryIter.remove();
      }
    }

    // Add new users
    for (Entry entry : expectedEntries.values()) {
      User.Name username = entry.username;
      if (!passwdEntries.containsKey(username)) {
        if (logger.isLoggable(Level.INFO)) {
          logger.info("Adding user to " + passwdFile + ": " + username);
        }
        passwdEntries.put(username, entry);
      }
    }
    return createPasswdFile(passwdEntries.values());
  }
}
