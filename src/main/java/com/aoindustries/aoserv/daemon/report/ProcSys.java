/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2020, 2021, 2022  AO Industries, Inc.
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

/**
 * Encapsulates the output of the <code>/proc/sys/fs</code> files.
 *
 * @author  AO Industries, Inc.
 */
public final class ProcSys {

  public final int filesAllocated;
  public final int filesUsed;
  public final int filesMax;
  public final int nrInodes;
  public final int nrFreeInodes;
  public final int inodePreshrink;
  public final int msgmax;
  public final int msgmnb;
  public final int msgmni;
  public final int nrMsg;
  public final int entropyAvail;
  public final int rtsigMax;
  public final int rtsigNr;
  public final int semmsl;
  public final int semmns;
  public final int semopm;
  public final int semmni;
  public final int nrSem;
  public final int shmall;
  public final int shmmax;
  public final int shmmni;
  public final int nrShm;
  public final int shmUsed;
  public final int threadsMax;

  public ProcSys() {
    filesAllocated
        = filesUsed
        = filesMax
        = nrInodes
        = nrFreeInodes
        = inodePreshrink
        = msgmax
        = msgmnb
        = msgmni
        = nrMsg
        = entropyAvail
        = rtsigMax
        = rtsigNr
        = semmsl
        = semmns
        = semopm
        = semmni
        = nrSem
        = shmall
        = shmmax
        = shmmni
        = nrShm
        = shmUsed
        = threadsMax
        = 0;
  }
}
