/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages SharedTomcatSite version 9.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_9_0_X extends VersionedTomcatSharedSiteManager<TomcatCommon_9_0_X> {

	HttpdTomcatSharedSiteManager_9_0_X(SharedTomcatSite tomcatSharedSite) throws SQLException, IOException {
		super(tomcatSharedSite);
	}

	@Override
	public TomcatCommon_9_0_X getTomcatCommon() {
		return TomcatCommon_9_0_X.getInstance();
	}
}
