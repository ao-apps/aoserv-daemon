/*
 * Copyright 2000-2013, 2014, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoindustries.lang.EmptyArrays;

/**
 * Encapsulates the output of the /bin/netstat -ln command.
 *
 * @author  AO Industries, Inc.
 */
final public class NetStatLN {

	final public String[] deviceIDs;
	final public int[] listens;

	public NetStatLN() {
		deviceIDs = EmptyArrays.EMPTY_STRING_ARRAY;
		listens = EmptyArrays.EMPTY_INT_ARRAY;
	}
}
