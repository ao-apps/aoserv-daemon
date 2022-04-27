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
import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;

/**
 * Encapsulates the output of the /proc/meminfo file.
 *
 * @author  AO Industries, Inc.
 */
public final class ProcMemory {

  public final int
      mem_total,
      mem_free,
      mem_shared,
      buffers,
      cached,
      swap_cached,
      active,
      inact_dirty,
      inact_clean,
      inact_target,
      high_total,
      high_free,
      low_total,
      low_free
  ;

  public ProcMemory() throws IOException, SQLException {
    int
        _mem_total = 0,
        _mem_free = 0,
        _mem_shared = 0,
        _buffers = 0,
        _cached = 0,
        _swap_cached = 0,
        _active = 0,
        _inact_dirty = 0,
        _inact_clean = 0,
        _inact_target = 0,
        _high_total = 0,
        _high_free = 0,
        _low_total = 0,
        _low_free = 0
    ;

    // Only the outer-most server tracks memory use
    if (AOServDaemon.getThisServer().getFailoverServer() == null) {
      // Parse for the values
      try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/meminfo")))) {
        String line;
        while ((line = in.readLine()) != null) {
          String[] words = Strings.split(line);
          String label = words[0];
          if ("MemTotal:".equals(label)) {
            _mem_total = Integer.parseInt(words[1]);
          } else if ("MemFree:".equals(label)) {
            _mem_free = Integer.parseInt(words[1]);
          } else if ("MemShared:".equals(label)) {
            _mem_shared = Integer.parseInt(words[1]);
          } else if ("Buffers:".equals(label)) {
            _buffers = Integer.parseInt(words[1]);
          } else if ("Cached:".equals(label)) {
            _cached = Integer.parseInt(words[1]);
          } else if ("SwapCached:".equals(label)) {
            _swap_cached = Integer.parseInt(words[1]);
          } else if ("Active:".equals(label)) {
            _active = Integer.parseInt(words[1]);
          } else if ("Inact_dirty:".equals(label)) {
            _inact_dirty = Integer.parseInt(words[1]);
          } else if ("Inact_clean:".equals(label)) {
            _inact_clean = Integer.parseInt(words[1]);
          } else if ("Inact_target:".equals(label)) {
            _inact_target = Integer.parseInt(words[1]);
          } else if ("HighTotal:".equals(label)) {
            _high_total = Integer.parseInt(words[1]);
          } else if ("HighFree:".equals(label)) {
            _high_free = Integer.parseInt(words[1]);
          } else if ("LowTotal:".equals(label)) {
            _low_total = Integer.parseInt(words[1]);
          } else if ("LowFree:".equals(label)) {
            _low_free = Integer.parseInt(words[1]);
          }
        }
      }
    }

    // Copy into instance
    this.mem_total = _mem_total;
    this.mem_free = _mem_free;
    this.mem_shared = _mem_shared;
    this.buffers = _buffers;
    this.cached = _cached;
    this.swap_cached = _swap_cached;
    this.active = _active;
    this.inact_dirty = _inact_dirty;
    this.inact_clean = _inact_clean;
    this.inact_target = _inact_target;
    this.high_total = _high_total;
    this.high_free = _high_free;
    this.low_total = _low_total;
    this.low_free = _low_free;
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
            + "?mem_total=" + mem_total
            + "&mem_free=" + mem_free
            + "&mem_shared=" + mem_shared
            + "&buffers=" + buffers
            + "&cached=" + cached
            + "&swap_cached=" + swap_cached
            + "&active=" + active
            + "&inact_dirty=" + inact_dirty
            + "&inact_clean=" + inact_clean
            + "&inact_target=" + inact_target
            + "&high_total=" + high_total
            + "&high_free=" + high_free
            + "&low_total=" + low_total
            + "&low_free=" + low_free
    ;
  }
}
