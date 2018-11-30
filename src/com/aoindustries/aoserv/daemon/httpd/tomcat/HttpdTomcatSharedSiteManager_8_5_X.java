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
 * Manages SharedTomcatSite version 8.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_8_5_X extends VersionedTomcatSharedSiteManager<TomcatCommon_8_5_X> {

	HttpdTomcatSharedSiteManager_8_5_X(SharedTomcatSite tomcatSharedSite) throws SQLException, IOException {
		super(tomcatSharedSite);
	}

	@Override
	public TomcatCommon_8_5_X getTomcatCommon() {
		return TomcatCommon_8_5_X.getInstance();
	}
}
