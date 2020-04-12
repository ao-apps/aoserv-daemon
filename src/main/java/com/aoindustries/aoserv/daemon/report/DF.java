/*
 * Copyright 2000-2013, 2014, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoindustries.lang.EmptyArrays;

/**
 * Encapsulates the output of the /bin/df command.
 *
 * @author  AO Industries, Inc.
 */
final public class DF {

	public final int[] device_majors;
	public final int[] device_minors;
	public final long[] totals;
	public final long[] useds;
	public final long[] frees;

	public DF() {
		device_majors=device_minors = EmptyArrays.EMPTY_INT_ARRAY;
		totals=useds=frees = EmptyArrays.EMPTY_LONG_ARRAY;
	}
}
