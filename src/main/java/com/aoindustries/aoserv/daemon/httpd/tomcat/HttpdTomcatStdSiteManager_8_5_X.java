/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.web.tomcat.PrivateTomcatSite;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages PrivateTomcatSite version 8.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_8_5_X extends VersionedTomcatStdSiteManager<TomcatCommon_8_5_X> {

	HttpdTomcatStdSiteManager_8_5_X(PrivateTomcatSite tomcatStdSite) throws SQLException, IOException {
		super(tomcatStdSite);
	}

	@Override
	public TomcatCommon_8_5_X getTomcatCommon() {
		return TomcatCommon_8_5_X.getInstance();
	}
}
