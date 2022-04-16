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
 * Encapsulates the output of the /proc/sys/fs files
 *
 * @author  AO Industries, Inc.
 */
public final class ProcSys {

	public final int
		files_allocated,
		files_used,
		files_max,
		nr_inodes,
		nr_free_inodes,
		inode_preshrink,
		msgmax,
		msgmnb,
		msgmni,
		nr_msg,
		entropy_avail,
		rtsig_max,
		rtsig_nr,
		semmsl,
		semmns,
		semopm,
		semmni,
		nr_sem,
		shmall,
		shmmax,
		shmmni,
		nr_shm,
		shm_used,
		threads_max
	;

	public ProcSys() {
		files_allocated
		=files_used
		=files_max
		=nr_inodes
		=nr_free_inodes
		=inode_preshrink
		=msgmax
		=msgmnb
		=msgmni
		=nr_msg
		=entropy_avail
		=rtsig_max
		=rtsig_nr
		=semmsl
		=semmns
		=semopm
		=semmni
		=nr_sem
		=shmall
		=shmmax
		=shmmni
		=nr_shm
		=shm_used
		=threads_max
		=0;
	}
}
