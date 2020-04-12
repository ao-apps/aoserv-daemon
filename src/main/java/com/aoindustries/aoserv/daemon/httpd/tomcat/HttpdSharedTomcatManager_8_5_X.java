/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;

/**
 * Manages SharedTomcat version 8.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_8_5_X extends VersionedSharedTomcatManager<TomcatCommon_8_5_X> {

	HttpdSharedTomcatManager_8_5_X(SharedTomcat sharedTomcat) {
		super(sharedTomcat);
	}

	@Override
	TomcatCommon_8_5_X getTomcatCommon() {
		return TomcatCommon_8_5_X.getInstance();
	}
}
