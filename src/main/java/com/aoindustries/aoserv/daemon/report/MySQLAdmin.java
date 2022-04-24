/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Encapsulates the output of the /usr/bin/mysqladmin command.
 *
 * @author  AO Industries, Inc.
 */
public final class MySQLAdmin extends DBReportData {

  public final int questions;
  public final int slow_queries;
  public final int opens;
  public final int flush_tables;
  public final int open_tables;
  public final float queries_per_second;

  public MySQLAdmin() throws IOException {
    // TODO: Do once per MySQLServer instance
    // TODO: Implement in NOC
    String user = "TODO"; // AOServDaemonConfiguration.getMySqlUser();
    String password = "TODO"; // AOServDaemonConfiguration.getMySqlPassword();
    if (user != null && user.length() > 0 && password != null && password.length() > 0) {
      String line = AOServDaemon.execCall(
          stdout -> {
            try (BufferedReader lineIn = new BufferedReader(new InputStreamReader(stdout))) {
              return lineIn.readLine();
            }
          },
          "/usr/bin/mysqladmin",
          "-h",
          IpAddress.LOOPBACK_IP,
          "-u",
          user,
          "--password=" + password, // TODO: use --login-path
          "status"
      );
      // Parse out the number of users
      String[] words = Strings.split(line);
      numUsers = Integer.parseInt(words[3]);
      questions = Integer.parseInt(words[5]);
      slow_queries = Integer.parseInt(words[8]);
      opens = Integer.parseInt(words[10]);
      flush_tables = Integer.parseInt(words[13]);
      open_tables = Integer.parseInt(words[16]);
      queries_per_second = Float.parseFloat(words[21]);
    } else {
      numUsers = questions = slow_queries = opens = flush_tables = open_tables = 0;
      queries_per_second = 0;
    }
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void main(String[] args) {
    try {
      System.err.println(new MySQLAdmin());
      System.exit(0);
    } catch (IOException err) {
      ErrorPrinter.printStackTraces(err, System.err);
      System.exit(1);
    }
  }

  @Override
  public String toString() {
    return
        super.toString()
            + "&questions=" + questions
            + "&slow_queries=" + slow_queries
            + "&opens=" + opens
            + "&flush_tables=" + flush_tables
            + "&open_tables=" + open_tables
            + "&queries_per_second=" + queries_per_second
    ;
  }
}
