/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2014, 2020, 2021, 2022  AO Industries, Inc.
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

import com.aoapps.lang.EmptyArrays;

/**
 * Encapsulates the output of the <code>/proc/net/dev</code> file.
 *
 * @author  AO Industries, Inc.
 */
public final class ProcNetDev {

  public final String[] deviceIds;
  public final long[] rxBytes;
  public final long[] rxPackets;
  public final long[] rxErrors;
  public final long[] rxDrop;
  public final long[] rxFifo;
  public final long[] rxFrame;
  public final long[] rxCompress;
  public final long[] rxMulticast;
  public final long[] txBytes;
  public final long[] txPackets;
  public final long[] txErrors;
  public final long[] txDrop;
  public final long[] txFifo;
  public final long[] txColls;
  public final long[] txCarrier;
  public final long[] txCompressed;

  public ProcNetDev() {
    deviceIds = EmptyArrays.EMPTY_STRING_ARRAY;
    rxBytes = rxPackets = rxErrors = rxDrop = rxFifo = rxFrame = rxCompress = rxMulticast = txBytes = txPackets =
        txErrors = txDrop = txFifo = txColls = txCarrier = txCompressed = EmptyArrays.EMPTY_LONG_ARRAY;
  }
}
