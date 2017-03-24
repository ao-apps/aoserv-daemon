/*
 * Copyright 2000-2009, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

/**
 * @author  AO Industries, Inc.
 */
abstract public class DBReportData {

	public int numUsers;

	public DBReportData() {
	}

	@Override
	public String toString() {
		return
			getClass().getName()
			+"?numUsers="+numUsers
		;
	}
}
