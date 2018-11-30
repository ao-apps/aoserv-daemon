/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.web.tomcat.HttpdTomcatStdSite;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages HttpdTomcatStdSite version 9.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_9_0_X extends VersionedTomcatStdSiteManager<TomcatCommon_9_0_X> {

	HttpdTomcatStdSiteManager_9_0_X(HttpdTomcatStdSite tomcatStdSite) throws SQLException, IOException {
		super(tomcatStdSite);
	}

	@Override
	public TomcatCommon_9_0_X getTomcatCommon() {
		return TomcatCommon_9_0_X.getInstance();
	}
}
