/*
 * Copyright 2008-2013, 2017, 2018, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;

/**
 * Manages SharedTomcat version 3.1 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_3_1 extends HttpdSharedTomcatManager_3_X<TomcatCommon_3_1> {

	HttpdSharedTomcatManager_3_1(SharedTomcat sharedTomcat) {
		super(sharedTomcat);
	}

	@Override
	TomcatCommon_3_1 getTomcatCommon() {
		return TomcatCommon_3_1.getInstance();
	}

	@Override
	protected String getOptDir() {
		return "apache-tomcat-3.1";
	}
}
