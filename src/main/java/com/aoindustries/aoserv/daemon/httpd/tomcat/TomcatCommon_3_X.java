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
import com.aoindustries.aoserv.client.web.tomcat.ContextDataSource;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;

/**
 * Some common code for Tomcat 3.X
 *
 * @author  AO Industries, Inc.
 */
public abstract class TomcatCommon_3_X extends TomcatCommon {

  TomcatCommon_3_X() {
    // Do nothing
  }

  /**
   * The list of files that are contained in [/var]/www/{site}/var/log directories.
   */
  public static final String[] tomcatLogFiles={
    "jasper.log",
    "jvm_crashes.log",
    "servlet.log",
    "servlet_err",
    "tomcat.log"
  };

  @Override
  public void writeHttpdTomcatDataSource(ContextDataSource dataSource, ChainWriter out) throws SQLException {
    throw new SQLException("Not implemented for Tomcat 3.X");
  }

  /**
   * Creates the test-tomcat.xml file in the provided conf directory.
   */
  public void createTestTomcatXml(String confDirectory, int uid, int gid, int mode, int uid_min, int gid_min) throws IOException {
    DaemonFileUtils.copyResource(TomcatCommon_3_X.class, "test-tomcat.xml", confDirectory+"/test-tomcat.xml", uid, gid, 0660, uid_min, gid_min);
  }

  public abstract void createWebDtd(String confDirectory, int uid, int gid, int mode, int uid_min, int gid_min) throws IOException;

  public abstract void createWebXml(String confDirectory, int uid, int gid, int mode, int uid_min, int gid_min) throws IOException;

  public void copyCocoonProperties1(OutputStream out) throws IOException {
    DaemonFileUtils.copyResource(TomcatCommon_3_X.class, "cocoon.properties.1", out);
  }

  public void copyCocoonProperties2(OutputStream out) throws IOException {
    DaemonFileUtils.copyResource(TomcatCommon_3_X.class, "cocoon.properties.2", out);
  }

  /**
   * Prints the default tomcat-users.xml file content for this version of Tomcat 3.X
   */
  public abstract void printTomcatUsers(ChainWriter out);

  /**
   * Gets the servlet API version.
   */
  //abstract String getServletApiVersion();

  /**
   * Gets the tomcat API version.
   */
  abstract String getTomcatApiVersion();
}
