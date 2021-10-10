/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008, 2009, 2017, 2021  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.httpd;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Indicates something may be stopped and started.
 *
 * @author  AO Industries, Inc.
 */
public interface StopStartable {

	/**
	 * Determines if the persistent processes for this site should be running.
	 */
	boolean isStartable() throws IOException, SQLException;

	/**
	 * Stops all processes for this website if it is running.
	 *
	 * @return  <code>true</code> if actually stopped or <code>false</code> if was already stopped
	 *          or {@code null} when unknown
	 */
	Boolean stop() throws IOException, SQLException;

	/**
	 * Starts all processes for this website if it is not running.
	 *
	 * @return  <code>true</code> if actually started or <code>false</code> if was already started
	 *          or {@code null} when unknown
	 */
	Boolean start() throws IOException, SQLException;
}
