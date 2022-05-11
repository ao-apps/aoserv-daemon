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

/**
 * Encapsulates the output of the <code>/proc/meminfo</code> file.
 *
 * @author  AO Industries, Inc.
 */
public final class ProcMemory {

  public final int memTotal;
  public final int memFree;
  public final int memShared;
  public final int buffers;
  public final int cached;
  public final int swapCached;
  public final int active;
  public final int inactDirty;
  public final int inactClean;
  public final int inactTarget;
  public final int highTotal;
  public final int highFree;
  public final int lowTotal;
  public final int lowFree;

  public ProcMemory() throws IOException, SQLException {
    int memTotalTmp = 0;
    int memFreeTmp = 0;
    int memSharedTmp = 0;
    int buffersTmp = 0;
    int cachedTmp = 0;
    int swapCachedTmp = 0;
    int activeTmp = 0;
    int inactDirtyTmp = 0;
    int inactCleanTmp = 0;
    int inactTargetTmp = 0;
    int highTotalTmp = 0;
    int highFreeTmp = 0;
    int lowTotalTmp = 0;
    int lowFreeTmp = 0;

    // Only the outer-most server tracks memory use
    if (AoservDaemon.getThisServer().getFailoverServer() == null) {
      // Parse for the values
      try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/meminfo")))) {
        String line;
        while ((line = in.readLine()) != null) {
          String[] words = Strings.split(line);
          String label = words[0];
          if ("MemTotal:".equals(label)) {
            memTotalTmp = Integer.parseInt(words[1]);
          } else if ("MemFree:".equals(label)) {
            memFreeTmp = Integer.parseInt(words[1]);
          } else if ("MemShared:".equals(label)) {
            memSharedTmp = Integer.parseInt(words[1]);
          } else if ("Buffers:".equals(label)) {
            buffersTmp = Integer.parseInt(words[1]);
          } else if ("Cached:".equals(label)) {
            cachedTmp = Integer.parseInt(words[1]);
          } else if ("SwapCached:".equals(label)) {
            swapCachedTmp = Integer.parseInt(words[1]);
          } else if ("Active:".equals(label)) {
            activeTmp = Integer.parseInt(words[1]);
          } else if ("Inact_dirty:".equals(label)) {
            inactDirtyTmp = Integer.parseInt(words[1]);
          } else if ("Inact_clean:".equals(label)) {
            inactCleanTmp = Integer.parseInt(words[1]);
          } else if ("Inact_target:".equals(label)) {
            inactTargetTmp = Integer.parseInt(words[1]);
          } else if ("HighTotal:".equals(label)) {
            highTotalTmp = Integer.parseInt(words[1]);
          } else if ("HighFree:".equals(label)) {
            highFreeTmp = Integer.parseInt(words[1]);
          } else if ("LowTotal:".equals(label)) {
            lowTotalTmp = Integer.parseInt(words[1]);
          } else if ("LowFree:".equals(label)) {
            lowFreeTmp = Integer.parseInt(words[1]);
          }
        }
      }
    }

    // Copy into instance
    this.memTotal = memTotalTmp;
    this.memFree = memFreeTmp;
    this.memShared = memSharedTmp;
    this.buffers = buffersTmp;
    this.cached = cachedTmp;
    this.swapCached = swapCachedTmp;
    this.active = activeTmp;
    this.inactDirty = inactDirtyTmp;
    this.inactClean = inactCleanTmp;
    this.inactTarget = inactTargetTmp;
    this.highTotal = highTotalTmp;
    this.highFree = highFreeTmp;
    this.lowTotal = lowTotalTmp;
    this.lowFree = lowFreeTmp;
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void main(String[] args) {
    try {
      System.err.println(new ProcMemory());
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
            + "?memTotal=" + memTotal
            + "&memFree=" + memFree
            + "&memShared=" + memShared
            + "&buffers=" + buffers
            + "&cached=" + cached
            + "&swapCached=" + swapCached
            + "&active=" + active
            + "&inactDirty=" + inactDirty
            + "&inactClean=" + inactClean
            + "&inactTarget=" + inactTarget
            + "&highTotal=" + highTotal
            + "&highFree=" + highFree
            + "&lowTotal=" + lowTotal
            + "&lowFree=" + lowFree;
  }
}
