/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2012, 2020, 2021  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.server;

import com.aoapps.lang.ProcessResult;
import java.io.IOException;

/**
 * The <code>ServerManager</code> controls stuff at a server level.
 *
 * @author  AO Industries, Inc.
 */
final public class PhysicalServerManager {

	private PhysicalServerManager() {
	}

	private static final String[] apcaccessStatusCommand = {
		"/sbin/apcaccess",
		"status"
	};

	/**
	 * Gets the current UPS status.
	 */
	public static String getUpsStatus() throws IOException {
		ProcessResult result = ProcessResult.exec(apcaccessStatusCommand);
		String stderr = result.getStderr();
		if(result.getExitVal()==0) {
			// Log any errors
			if(stderr.length()>0) System.err.println(stderr);
			return result.getStdout();
		} else {
			throw new IOException(stderr);
		}
	}
}
