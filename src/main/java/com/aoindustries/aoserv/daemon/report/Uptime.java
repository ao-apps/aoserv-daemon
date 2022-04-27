/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

import com.aoapps.lang.util.ErrorPrinter;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;

/**
 * Encapsulates the output of the /usr/bin/uptime command.
 *
 * @author  AO Industries, Inc.
 */
public final class Uptime {

  private static final String[] cmd = {"/usr/bin/uptime"};

  public final int numUsers;
  public final float load;

  public Uptime() throws IOException, SQLException {
    String line = AOServDaemon.execCall(
        stdout -> {
          try (BufferedReader in = new BufferedReader(new InputStreamReader(stdout))) {
            return in.readLine();
          }
        },
        cmd
    );
    if (line == null) {
      throw new EOFException("Nothing output by /usr/bin/uptime");
    }

    // Find the third colon, then back two commas
    int pos = line.lastIndexOf(':');
    pos = line.lastIndexOf(',', pos - 1);
    pos = line.lastIndexOf(',', pos - 1) + 1;

    // skip past spaces
    int len = line.length();
    while (pos < len && line.charAt(pos) == ' ') {
      pos++;
    }

    // find next space
    int pos2 = pos + 1;
    while (pos2 < len && line.charAt(pos2) != ' ') {
      pos2++;
    }

    // Parse the number of users
    numUsers = Integer.parseInt(line.substring(pos, pos2));

    // Only the top-level server keeps track of load
    if (AOServDaemon.getThisServer().getFailoverServer() == null) {
      // Find the next colon
      pos = line.indexOf(':', pos2 + 1) + 1;

      // Skip any whitespace
      while (pos < len && line.charAt(pos) == ' ') {
        pos++;
      }

      // Find the next comma
      pos2 = line.indexOf(',', pos);

      load = Float.parseFloat(line.substring(pos, pos2));
    } else {
      load = 0.00f;
    }
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void main(String[] args) {
    try {
      System.err.println(new Uptime());
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
            + "?numUsers=" + numUsers
            + "&load=" + load
    ;
  }
}
