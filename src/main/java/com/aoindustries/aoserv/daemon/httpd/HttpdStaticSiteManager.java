/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2007-2013, 2014, 2015, 2017, 2018  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.StaticSite;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.validation.ValidationException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Manages StaticSite configurations.  These are the most stripped-down
 * form of website.  Should not allow any sort of server-side execution.
 * Perhaps we could sell these for $1/month?
 *
 * @author  AO Industries, Inc.
 */
public class HttpdStaticSiteManager extends HttpdSiteManager {

	/**
	 * Gets the specific manager for one type of web site.
	 */
	static HttpdStaticSiteManager getInstance(StaticSite staticSite) throws SQLException, IOException {
		return new HttpdStaticSiteManager(staticSite);
	}

	final protected StaticSite staticSite;

	private HttpdStaticSiteManager(StaticSite staticSite) throws SQLException, IOException {
		super(staticSite.getHttpdSite());
		this.staticSite = staticSite;
	}

	@Override
	protected void buildSiteDirectory(
		UnixFile siteDirectory,
		String optSlash,
		Set<Site> sitesNeedingRestarted,
		Set<SharedTomcat> sharedTomcatsNeedingRestarted,
		Set<UnixFile> restorecon
	) throws IOException, SQLException {
		final boolean isAuto = !httpdSite.isManual();
		final int apacheUid = getApacheUid();
		final int uid = httpdSite.getLinuxServerAccount().getUid().getId();
		final int gid = httpdSite.getLinuxServerGroup().getGid().getId();

		// Create wwwDirectory if needed
		Stat siteDirectoryStat = siteDirectory.getStat();
		if(!siteDirectoryStat.exists()) {
			siteDirectory.mkdir(false, 0700);
			siteDirectoryStat = siteDirectory.getStat();
		} else if(!siteDirectoryStat.isDirectory()) throw new IOException("Not a directory: "+siteDirectory);

		// New if still owned by root
		final boolean isNew = siteDirectoryStat.getUid() == UnixFile.ROOT_UID;

		// conf/
		if(isNew || isAuto) DaemonFileUtils.mkdir(new UnixFile(siteDirectory, "conf", false), 0775, uid, gid);
		// htdocs/
		UnixFile htdocsDirectory = new UnixFile(siteDirectory, "htdocs", false);
		if(isNew || isAuto) DaemonFileUtils.mkdir(htdocsDirectory, 0775, uid, gid);
		// htdocs/index.html
		if(isNew) createTestIndex(new UnixFile(htdocsDirectory, "index.html", false));

		// Complete, set permission and ownership
		siteDirectoryStat = siteDirectory.getStat();
		if(siteDirectoryStat.getMode()!=0770) siteDirectory.setMode(0770);
		if(siteDirectoryStat.getUid()!=apacheUid || siteDirectoryStat.getGid()!=gid) siteDirectory.chown(apacheUid, gid);
	}

	/**
	 * No CGI.
	 */
	@Override
	protected boolean enableCgi() {
		return false;
	}

	/**
	 * No PHP.
	 */
	@Override
	protected boolean enablePhp() {
		return false;
	}

	/**
	 * No anonymous FTP directory.
	 */
	@Override
	public boolean enableAnonymousFtp() {
		return false;
	}

	@Override
	public SortedMap<String,WebAppSettings> getWebapps() throws IOException, SQLException {
		try {
			SortedMap<String,WebAppSettings> webapps = new TreeMap<>();
			webapps.put(
				"",
				new WebAppSettings(
					PosixPath.valueOf(
						HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory().toString()
							+'/'
							+httpdSite.getName()
							+"/htdocs"
					),
					httpdSite.getEnableHtaccess() ? "AuthConfig Indexes Limit" : "None",
					httpdSite.getEnableSsi(),
					httpdSite.getEnableIndexes(),
					httpdSite.getEnableFollowSymlinks(),
					enableCgi()
				)
			);
			return webapps;
		} catch(ValidationException e) {
			throw new IOException(e);
		}
	}
}
