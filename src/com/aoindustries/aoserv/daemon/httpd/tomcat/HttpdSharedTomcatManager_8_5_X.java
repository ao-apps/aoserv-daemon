/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages HttpdSharedTomcat version 8.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_8_5_X extends VersionedSharedTomcatManager<TomcatCommon_8_5_X> {

	HttpdSharedTomcatManager_8_5_X(HttpdSharedTomcat sharedTomcat) {
		super(sharedTomcat);
	}

	@Override
	TomcatCommon_8_5_X getTomcatCommon() {
		return TomcatCommon_8_5_X.getInstance();
	}

	@Override
	protected boolean upgradeSharedTomcatDirectory(String optSlash, UnixFile siteDirectory) throws IOException, SQLException {
		// Upgrade Tomcat
		boolean needsRestart = getTomcatCommon().upgradeTomcatDirectory(
			optSlash,
			siteDirectory,
			sharedTomcat.getLinuxServerAccount().getUid().getId(),
			sharedTomcat.getLinuxServerGroup().getGid().getId()
		);
		return needsRestart;
	}
}
