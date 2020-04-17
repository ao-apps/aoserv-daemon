/*
 * Copyright 2012, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.server;

import com.aoindustries.lang.ProcessResult;
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
