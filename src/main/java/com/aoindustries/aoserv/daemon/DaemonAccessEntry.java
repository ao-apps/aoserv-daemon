/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2009, 2015  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon;

/**
 * @see  AOServDaemonServer#getDaemonAccessEntry
 *
 * @author  AO Industries, Inc.
 */
public class DaemonAccessEntry {

	public final long key;
	public final int command;
	public final String param1;
	public final String param2;
	public final String param3;
	public final String param4;
	public final long created;

	public DaemonAccessEntry(
		long key,
		int command,
		String param1,
		String param2,
		String param3,
		String param4
	) {
		this.key = key;
		this.command = command;
		this.param1 = param1;
		this.param2 = param2;
		this.param3 = param3;
		this.param4 = param4;
		this.created = System.currentTimeMillis();
	}
}