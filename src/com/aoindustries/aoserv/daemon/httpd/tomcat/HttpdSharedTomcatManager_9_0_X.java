/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdSharedTomcat;

/**
 * Manages HttpdSharedTomcat version 9.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_9_0_X extends VersionedSharedTomcatManager<TomcatCommon_9_0_X> {

	HttpdSharedTomcatManager_9_0_X(HttpdSharedTomcat sharedTomcat) {
		super(sharedTomcat);
	}

	@Override
	TomcatCommon_9_0_X getTomcatCommon() {
		return TomcatCommon_9_0_X.getInstance();
	}
}
