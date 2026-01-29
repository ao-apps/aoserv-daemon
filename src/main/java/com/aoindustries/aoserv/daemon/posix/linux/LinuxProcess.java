/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2018, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.posix.linux;

import com.aoapps.collections.AoCollections;
import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.daemon.posix.PosixProcess;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A <code>PosixProcess</code> represents a process
 * running on any POSIX machine.
 *
 * @author  AO Industries, Inc.
 */
public class LinuxProcess extends PosixProcess {

  private static final File proc = new File("/proc");

  /**
   * The <code>/proc/<i>pid</i></code> file is cached once created.
   */
  private File processProc;

  /**
   * Constructs a Linux process given its process ID.
   */
  public LinuxProcess(int pid) {
    super(pid);
  }

  /**
   * Determines the group ID of the currently running process.
   * The GID is considered the group owner of the file in the
   * /proc directory.  If the process is not running, a
   * FileNotFoundException is thrown.
   */
  @Override
  public int getGid() throws IOException {
    return new PosixFile(getProc().getPath()).getStat().getGid();
  }

  /**
   * Gets the directory that contains the proc info.
   *
   * @return  the <code>File</code>
   * @exception  IOException if the proc is not mounted
   */
  private File getProc() {
    synchronized (this) {
      if (processProc == null) {
        processProc = new File(proc, String.valueOf(pid));
      }
      return processProc;
    }
  }

  /**
   * Determines the user ID of the currently running process.
   * The UID is considered the owner of the file in the
   * /proc directory.  If the process is not running, a
   * FileNotFoundException is thrown.
   */
  @Override
  public int getUid() throws IOException {
    return new PosixFile(getProc().getPath()).getStat().getUid();
  }

  /**
   * Determines if the process is currently running.  The process
   * is considered running if a directory exists in /proc.
   */
  @Override
  public boolean isRunning() throws IOException {
    return getProc().exists();
  }

  /**
   * Gets the command line from <code>/proc/<i>pid</i>/cmdline</code>, split
   * on null bytes.
   */
  public String[] getCmdline() throws IOException {
    List<String> split = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    File cmdlineFile = new File(getProc(), "cmdline");
    try (Reader in = new FileReader(cmdlineFile)) {
      int ch;
      while ((ch = in.read()) != -1) {
        if (ch == 0) {
          split.add(sb.toString());
          sb.setLength(0);
        } else {
          sb.append((char) ch);
        }
      }
    }
    if (sb.length() != 0) {
      split.add(sb.toString());
    }
    return split.toArray(new String[split.size()]);
  }

  /**
   * Gets the status from <code>/proc/<i>pid</i>/status</code>.
   * The colon (:) is removed from the field names.
   *
   * @see  LinuxProcess#getStatus(java.lang.String)
   */
  public Map<String, String> getStatus() throws IOException {
    File statusFile = new File(getProc(), "status");
    final int expectedMaxLength = 45; // "wc -l /proc/*/status" shows maximum 45 lines in kernel 3.10.0-514.16.1.el7.x86_64
    Map<String, String> status = AoCollections.newLinkedHashMap(expectedMaxLength);
    try (BufferedReader in = new BufferedReader(new FileReader(statusFile))) {
      String line;
      while ((line = in.readLine()) != null) {
        // Have seen empty lines, skip them
        if (!line.isEmpty()) {
          int tabPos = line.indexOf('\t');
          if (tabPos == -1) {
            throw new IOException("No tab found in line from " + statusFile + ": " + line);
          }
          if (tabPos < 1) {
            throw new IOException("Empty name column from " + statusFile + ": " + line);
          }
          if (line.charAt(tabPos - 1) != ':') {
            throw new IOException("Not colon before tab from " + statusFile + ": " + line);
          }
          String name = line.substring(0, tabPos - 1);
          String value = line.substring(tabPos + 1);
          if (status.put(name, value) != null) {
            throw new IOException("Duplicate name from " + statusFile + ": " + name);
          }
        }
      }
    }
    return status;
  }

  /**
   * Gets one field of the status from <code>/proc/<i>pid</i>/status</code>.
   * The colon (:) is removed from the field names.
   *
   * @return  the corresponding value or {@code null} if not found
   *
   * @see  LinuxProcess#getStatus()
   */
  public String getStatus(String name) throws IOException {
    File statusFile = new File(getProc(), "status");
    try (BufferedReader in = new BufferedReader(new FileReader(statusFile))) {
      String line;
      while ((line = in.readLine()) != null) {
        // Have seen empty lines, skip them
        if (!line.isEmpty()) {
          int tabPos = line.indexOf('\t');
          if (tabPos == -1) {
            throw new IOException("No tab found in line from " + statusFile + ": " + line);
          }
          if (tabPos < 1) {
            throw new IOException("Empty name column from " + statusFile + ": " + line);
          }
          if (line.charAt(tabPos - 1) != ':') {
            throw new IOException("Not colon before tab from " + statusFile + ": " + line);
          }
          if (name.equals(line.substring(0, tabPos - 1))) {
            return line.substring(tabPos + 1);
          }
        }
      }
    }
    return null;
  }
}
