/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008-2013, 2014, 2015, 2016, 2017, 2018, 2021, 2022  AO Industries, Inc.
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
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Some common code for Tomcat 3.1
 *
 * @author  AO Industries, Inc.
 */
final class TomcatCommon_3_1 extends TomcatCommon_3_X {

  private static final TomcatCommon_3_1 instance = new TomcatCommon_3_1();

  static TomcatCommon_3_1 getInstance() {
    return instance;
  }

  private TomcatCommon_3_1() {
    // Do nothing
  }

  @Override
  protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
    return EnumSet.of(
        OperatingSystemConfiguration.getOperatingSystemConfiguration().getJdk17PackageName(),
        PackageManager.PackageName.APACHE_TOMCAT_3_1
    );
  }

  @Override
  public void createWebDtd(String confDirectory, int uid, int gid, int mode, int uidMin, int gidMin) throws IOException {
    DaemonFileUtils.copyResource(TomcatCommon_3_1.class, "web.dtd-3.1", confDirectory + "/web.dtd", uid, gid, mode, uidMin, gidMin);
  }

  @Override
  public void createWebXml(String confDirectory, int uid, int gid, int mode, int uidMin, int gidMin) throws IOException {
    DaemonFileUtils.copyResource(TomcatCommon_3_1.class, "web.xml-3.1", confDirectory + "/web.xml", uid, gid, mode, uidMin, gidMin);
  }

  @Override
  public void printTomcatUsers(ChainWriter out) {
    out.print("<tomcat-users>\n"
        + "  <user name=\"tomcat\" password=\"tomcat\" roles=\"tomcat\" />\n"
        + "</tomcat-users>\n");
  }

  // String getServletApiVersion() {
  //     return "3.1";
  // }

  @Override
  String getTomcatApiVersion() {
    return "3.1";
  }

  @Override
  protected String getApacheTomcatDir() {
    return "apache-tomcat-3.1";
  }
}
