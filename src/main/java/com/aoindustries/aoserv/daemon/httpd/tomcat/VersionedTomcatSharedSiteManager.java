/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2018, 2019, 2021, 2022  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.io.IoUtils;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages shared aspects of SharedTomcatSite version 8.5 and above.
 *
 * @author  AO Industries, Inc.
 */
abstract class VersionedTomcatSharedSiteManager<TC extends VersionedTomcatCommon> extends HttpdTomcatSharedSiteManager<TC> {

	VersionedTomcatSharedSiteManager(SharedTomcatSite tomcatSharedSite) throws SQLException, IOException {
		super(tomcatSharedSite);
	}

	/**
	 * TODO: Support upgrades in-place
	 */
	@Override
	protected void buildSiteDirectoryContents(String optSlash, PosixFile siteDirectory, boolean isUpgrade) throws IOException, SQLException {
		if(isUpgrade) throw new IllegalArgumentException("In-place upgrade not supported");
		/*
		 * Resolve and allocate stuff used throughout the method
		 */
		final String siteDir = siteDirectory.getPath();
		final int uid = httpdSite.getLinuxServerAccount().getUid().getId();
		final int gid = httpdSite.getLinuxServerGroup().getGid().getId();

		final TC tomcatCommon = getTomcatCommon();
		final String apacheTomcatDir = tomcatCommon.getApacheTomcatDir();

		/*
		 * Create the skeleton of the site, the directories and links.
		 */
		DaemonFileUtils.mkdir(siteDir + "/bin", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir + "/conf", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir + "/daemon", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir + "/webapps", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE, 0775, uid, gid);
		// TODO: Do no create these on upgrade:
		DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/classes", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/lib", 0770, uid, gid);

		Server thisServer = AOServDaemon.getThisServer();
		int uid_min = thisServer.getUidMin().getId();
		int gid_min = thisServer.getGidMin().getId();

		/*
		 * Write the ROOT/WEB-INF/web.xml file.
		 */
		// TODO: Do no create these on upgrade:
		String webXML = siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/web.xml";
		try (
			InputStream in = new FileInputStream("/opt/" + apacheTomcatDir + "/webapps/ROOT/WEB-INF/web.xml");
			OutputStream out = new PosixFile(webXML).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min)
		) {
			IoUtils.copy(in, out);
		}
	}

	@Override
	protected boolean rebuildConfigFiles(PosixFile siteDirectory, Set<PosixFile> restorecon) {
		// No configs to rebuild
		return false;
	}

	@Override
	protected boolean upgradeSiteDirectoryContents(String optSlash, PosixFile siteDirectory) throws IOException, SQLException {
		// Nothing to do
		return false;
	}
}
