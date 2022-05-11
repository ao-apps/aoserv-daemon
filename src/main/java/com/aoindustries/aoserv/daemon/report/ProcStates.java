/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.report;

import com.aoapps.lang.Strings;
import com.aoapps.lang.util.ErrorPrinter;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.linux.LinuxId;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;

/**
 * Encapsulates the output of the <code>/proc/<var>PID</var>/status</code> files.
 *
 * @author  AO Industries, Inc.
 */
public final class ProcStates {

  private static final File proc = new File("/proc");

  public final int totalSleep;
  public final int userSleep;
  public final int totalRun;
  public final int userRun;
  public final int totalZombie;
  public final int userZombie;
  public final int totalTrace;
  public final int userTrace;
  public final int totalUninterruptible;
  public final int userUninterruptible;
  public final int totalUnknown;
  public final int userUnknown;

  public ProcStates() throws IOException, SQLException {
    int totalSleepTmp = 0;
    int userSleepTmp = 0;
    int totalRunTmp = 0;
    int userRunTmp = 0;
    int totalZombieTmp = 0;
    int userZombieTmp = 0;
    int totalTraceTmp = 0;
    int userTraceTmp = 0;
    int totalUninterruptibleTmp = 0;
    int userUninterruptibleTmp = 0;
    int totalUnknownTmp = 0;
    int userUnknownTmp = 0;

    Server thisServer = AoservDaemon.getThisServer();
    boolean isOuterServer = thisServer.getFailoverServer() == null;
    int uidMin = thisServer.getUidMin().getId();

    // Parse for the values
    String[] list = proc.list();
    int len = list.length;
    for (int c = 0; c < len; c++) {
      String filename = list[c];
      char ch = filename.charAt(0);
      if (ch >= '0' && ch <= '9') {
        File file = new File(proc, filename);
        if (file.isDirectory()) {
          try {
            String state = null;
            int uid = -1;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(new File(file, "status"))))) {
              String line;
              while ((state == null || uid == -1) && (line = in.readLine()) != null) {
                if (line.startsWith("State:")) {
                  String[] words = Strings.split(line);
                  state = words[1];
                } else if (line.startsWith("Uid:")) {
                  String[] words = Strings.split(line);
                  uid = Integer.parseInt(words[1]);
                }
              }
              if (isOuterServer) {
                if (state == null) {
                  totalUnknownTmp++;
                } else {
                  ch = state.charAt(0);
                  if (ch == 'S') {
                    totalSleepTmp++;
                  } else if (ch == 'R') {
                    totalRunTmp++;
                  } else if (ch == 'Z') {
                    totalZombieTmp++;
                  } else if (ch == 'T') {
                    totalTraceTmp++;
                  } else if (ch == 'D') {
                    totalUninterruptibleTmp++;
                  } else {
                    totalUnknownTmp++;
                  }
                }
              }
              if (
                  uid >= uidMin
                      && thisServer.getLinuxServerAccount(LinuxId.valueOf(uid)) != null
              ) {
                if (state == null) {
                  userUnknownTmp++;
                } else {
                  ch = state.charAt(0);
                  if (ch == 'S') {
                    userSleepTmp++;
                  } else if (ch == 'R') {
                    userRunTmp++;
                  } else if (ch == 'Z') {
                    userZombieTmp++;
                  } else if (ch == 'T') {
                    userTraceTmp++;
                  } else if (ch == 'D') {
                    userUninterruptibleTmp++;
                  } else {
                    userUnknownTmp++;
                  }
                }
              }
            }
          } catch (FileNotFoundException err) {
            // Normal if the process has terminated
          } catch (ValidationException e) {
            throw new IOException(e);
          }
        }
      }
    }

    // Copy into instance
    this.totalSleep = totalSleepTmp;
    this.userSleep = userSleepTmp;
    this.totalRun = totalRunTmp;
    this.userRun = userRunTmp;
    this.totalZombie = totalZombieTmp;
    this.userZombie = userZombieTmp;
    this.totalTrace = totalTraceTmp;
    this.userTrace = userTraceTmp;
    this.totalUninterruptible = totalUninterruptibleTmp;
    this.userUninterruptible = userUninterruptibleTmp;
    this.totalUnknown = totalUnknownTmp;
    this.userUnknown = userUnknownTmp;
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void main(String[] args) {
    try {
      System.err.println(new ProcStates());
      System.exit(0);
    } catch (IOException err) {
      ErrorPrinter.printStackTraces(err, System.err);
      System.exit(1);
    } catch (SQLException err) {
      ErrorPrinter.printStackTraces(err, System.err);
      System.exit(2);
    }
  }

  @Override
  public String toString() {
    return
        getClass().getName()
            + "?totalSleep=" + totalSleep
            + "&userSleep=" + userSleep
            + "&totalRun=" + totalRun
            + "&userRun=" + userRun
            + "&totalZombie=" + totalZombie
            + "&userZombie=" + userZombie
            + "&totalTrace=" + totalTrace
            + "&userTrace=" + userTrace
            + "&totalUninterruptible=" + totalUninterruptible
            + "&userUninterruptible=" + userUninterruptible
            + "&totalUnknown=" + totalUnknown
            + "&userUnknown=" + userUnknown;
  }
}
