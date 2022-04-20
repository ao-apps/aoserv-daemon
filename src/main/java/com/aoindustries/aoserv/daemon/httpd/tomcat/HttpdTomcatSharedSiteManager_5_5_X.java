/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2007-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2021, 2022  AO Industries, Inc.
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

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages SharedTomcatSite version 5.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_5_5_X extends HttpdTomcatSharedSiteManager<TomcatCommon_5_5_X> {

  HttpdTomcatSharedSiteManager_5_5_X(SharedTomcatSite tomcatSharedSite) throws SQLException, IOException {
    super(tomcatSharedSite);
  }

  @Override
  protected void buildSiteDirectoryContents(String optSlash, PosixFile siteDirectory, boolean isUpgrade) throws IOException, SQLException {
    if (isUpgrade) {
      throw new IllegalArgumentException("In-place upgrade not supported");
    }
    /*
     * Resolve and allocate stuff used throughout the method
     */
    final String siteDir = siteDirectory.getPath();
    final int uid = httpdSite.getLinuxServerAccount().getUid().getId();
    final int gid = httpdSite.getLinuxServerGroup().getGid().getId();

    /*
     * Create the skeleton of the site, the directories and links.
     */
    DaemonFileUtils.mkdir(siteDir+"/bin", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir+"/conf", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir+"/daemon", 0770, uid, gid);
    DaemonFileUtils.ln("var/log", siteDir+"/logs", uid, gid);
    DaemonFileUtils.mkdir(siteDir+"/var", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir+"/var/log", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE, 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);

    Server thisServer = AOServDaemon.getThisServer();
    int uid_min = thisServer.getUidMin().getId();
    int gid_min = thisServer.getGidMin().getId();

    /*
     * Write the ROOT/WEB-INF/web.xml file.
     */
    String webXML=siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/web.xml";
    try (
      ChainWriter out = new ChainWriter(
        new BufferedOutputStream(
          new PosixFile(webXML).getSecureOutputStream(uid, gid, 0664, false, uid_min, gid_min)
        )
      )
    ) {
      out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
          + "<web-app xmlns=\"http://java.sun.com/xml/ns/j2ee\"\n"
          + "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
          + "    xsi:schemaLocation=\"http://java.sun.com/xml/ns/j2ee http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd\"\n"
          + "    version=\"2.4\">\n"
          + "  <display-name>Welcome to Tomcat</display-name>\n"
          + "  <description>\n"
          + "     Welcome to Tomcat\n"
          + "  </description>\n"
          + "</web-app>\n");
    }
  }

  @Override
  public TomcatCommon_5_5_X getTomcatCommon() {
    return TomcatCommon_5_5_X.getInstance();
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
