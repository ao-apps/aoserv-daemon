/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2017, 2019, 2020, 2021, 2022  AO Industries, Inc.
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
import com.aoindustries.aoserv.daemon.AoservDaemon;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the output of the <code>/proc/stat</code> file.
 *
 * @author  AO Industries, Inc.
 */
public final class ProcStat {

  public final long[] userCpuTimes;
  public final long[] niceCpuTimes;
  public final long[] sysCpuTimes;

  public final long pagesIn;
  public final long pagesOut;

  public final long swapsIn;
  public final long swapsOut;

  public final long contextSwitches;
  public final long processes;

  public ProcStat() throws IOException, SQLException {
    List<Long> myUserCpuTimes = new ArrayList<>();
    List<Long> myNiceCpuTimes = new ArrayList<>();
    List<Long> mySysCpuTimes = new ArrayList<>();
    long myPagesIn = 0;
    long myPagesOut = 0;
    long mySwapsIn = 0;
    long mySwapsOut = 0;
    long myContextSwitches = 0;
    long myProcesses = 0;

    // Only the outer-most server tracks these stats
    if (AoservDaemon.getThisServer().getFailoverServer() == null) {
      // Parse for the values
      try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/stat")))) {
        String line;
        while ((line = in.readLine()) != null) {
          String[] words = Strings.split(line);
          String label = words[0];
          if (
              label.length() > 3
                  && label.startsWith("cpu")
          ) {
            myUserCpuTimes.add(Long.valueOf(words[1]) * 10);
            myNiceCpuTimes.add(Long.valueOf(words[2]) * 10);
            mySysCpuTimes.add(Long.valueOf(words[3]) * 10);
          } else if ("page".equals(label)) {
            myPagesIn = Long.parseLong(words[1]);
            myPagesOut = Long.parseLong(words[2]);
          } else if ("swap".equals(label)) {
            mySwapsIn = Long.parseLong(words[1]);
            mySwapsOut = Long.parseLong(words[2]);
          } else if ("ctxt".equals(label)) {
            myContextSwitches = Long.parseLong(words[1]);
          } else if ("processes".equals(label)) {
            myProcesses = Long.parseLong(words[1]);
          }
        }
      }
    }

    // Copy into instance
    this.userCpuTimes = getLongArray(myUserCpuTimes);
    this.niceCpuTimes = getLongArray(myNiceCpuTimes);
    this.sysCpuTimes = getLongArray(mySysCpuTimes);
    this.pagesIn = myPagesIn;
    this.pagesOut = myPagesOut;
    this.swapsIn = mySwapsIn;
    this.swapsOut = mySwapsOut;
    this.contextSwitches = myContextSwitches;
    this.processes = myProcesses;
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void main(String[] args) {
    try {
      System.err.println(new ProcStat());
      System.exit(0);
    } catch (IOException err) {
      ErrorPrinter.printStackTraces(err, System.err);
      System.exit(1);
    } catch (SQLException err) {
      ErrorPrinter.printStackTraces(err, System.err);
      System.exit(2);
    }
  }

  private static long[] getLongArray(List<Long> list) {
    int len = list.size();
    long[] la = new long[len];
    for (int c = 0; c < len; c++) {
      la[c] = list.get(c);
    }
    return la;
  }

  /*
  private static int[] getIntArray(ArrayList list) {
    int len=list.size();
    int[] ia=new int[len];
    for (int c=0;c<len;c++) ia[c]=((Integer)list.get(c)).intValue();
    return ia;
  }*/

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getClass().getName());
    for (int c = 0; c < userCpuTimes.length; c++) {
      sb
          .append(c == 0 ? '?' : '&')
          .append("cpu")
          .append(c)
          .append("=(user=")
          .append(userCpuTimes[c])
          .append(",nice=")
          .append(niceCpuTimes[c])
          .append(",sys=")
          .append(sysCpuTimes[c])
          .append(')');
    }
    sb.append("&pages=(in=").append(pagesIn).append(",out=").append(pagesOut).append(')');
    sb.append("&swaps=(in=").append(swapsIn).append(",out=").append(swapsOut).append(')');
    sb.append("&contexts=").append(contextSwitches);
    sb.append("&processes=").append(processes);
    return sb.toString();
  }
}
