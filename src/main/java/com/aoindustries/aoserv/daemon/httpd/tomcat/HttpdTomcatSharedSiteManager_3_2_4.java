/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2007-2013, 2015, 2017, 2018, 2020, 2021, 2022  AO Industries, Inc.
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
import com.aoapps.net.InetAddress;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.JkProtocol;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Version;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages SharedTomcatSite version 3.2.4 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_3_2_4 extends HttpdTomcatSharedSiteManager_3_X<TomcatCommon_3_2_4> {

  HttpdTomcatSharedSiteManager_3_2_4(SharedTomcatSite tomcatSharedSite) throws SQLException, IOException {
    super(tomcatSharedSite);
  }

  @Override
  public TomcatCommon_3_2_4 getTomcatCommon() {
    return TomcatCommon_3_2_4.getInstance();
  }

  @Override
  protected byte[] buildServerXml(PosixFile siteDirectory, String autoWarning) throws IOException, SQLException {
    final String siteName = httpdSite.getName();
    final String siteDir = siteDirectory.getPath();
    AoservConnector conn = AoservDaemon.getConnector();
    final Version htv = tomcatSite.getHttpdTomcatVersion();
    final HttpdOperatingSystemConfiguration httpdConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
    final PosixPath wwwgroupDirectory = httpdConfig.getHttpdSharedTomcatsDirectory();

    // Build to RAM first
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(bout)) {
      // TODO: Encoding UTF-8 here? (and other versions)
      out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
      if (!httpdSite.isManual()) {
        out.print(autoWarning);
      }
      out.print("<Host>\n"
          + "  <xmlmapper:debug level=\"0\" />\n"
          + "  <Logger name=\"tc_log\" verbosityLevel = \"INFORMATION\" path=\"").textInXmlAttribute(siteDir).print("/var/log/tomcat.log\" />\n"
          + "  <Logger name=\"servlet_log\" path=\"").textInXmlAttribute(siteDir).print("/var/log/servlet.log\" />\n"
          + "  <Logger name=\"JASPER_LOG\" path=\"").textInXmlAttribute(siteDir).print("/var/log/jasper.log\" verbosityLevel = \"INFORMATION\" />\n"
          + "\n"
          + "  <ContextManager debug=\"0\" home=\"").textInXmlAttribute(siteDir).print("\" workDir=\"")
          .textInXmlAttribute(wwwgroupDirectory).print('/').textInXmlAttribute(tomcatSharedSite.getHttpdSharedTomcat().getName())
          .print("/work/").textInXmlAttribute(siteName).print("\" showDebugInfo=\"true\" >\n"
          + "    <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
          + "    <ContextInterceptor className=\"org.apache.tomcat.context.LoaderInterceptor\" />\n"
          + "    <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
          + "    <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper1\" debug=\"0\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.InvokerInterceptor\" debug=\"0\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.StaticInterceptor\" debug=\"0\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.session.StandardSessionInterceptor\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.AccessInterceptor\" debug=\"0\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleRealm\" debug=\"0\" />\n"
          + "    <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n");

      for (Worker worker : tomcatSite.getHttpdWorkers()) {
        Bind netBind = worker.getBind();
        String protocol = worker.getHttpdJkProtocol(conn).getProtocol(conn).getProtocol();

        out.print("    <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
            + "      <Parameter name=\"handler\" value=\"");
        switch (protocol) {
          case JkProtocol.AJP12:
            out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
            break;
          case JkProtocol.AJP13:
            out.print("org.apache.tomcat.service.connector.Ajp13ConnectionHandler");
            break;
          default:
            throw new IllegalArgumentException("Unknown AJP version: " + htv);
        }
        out.print("\"/>\n"
            + "      <Parameter name=\"port\" value=\"").textInXmlAttribute(netBind.getPort().getPort()).print("\"/>\n");
        InetAddress ip = netBind.getIpAddress().getInetAddress();
        if (!ip.isUnspecified()) {
          out.print("      <Parameter name=\"inet\" value=\"").textInXmlAttribute(ip).print("\"/>\n");
        }
        out.print("      <Parameter name=\"max_threads\" value=\"30\"/>\n"
            + "      <Parameter name=\"max_spare_threads\" value=\"10\"/>\n"
            + "      <Parameter name=\"min_spare_threads\" value=\"1\"/>\n"
            + "    </Connector>\n"
        );
      }
      for (Context htc : tomcatSite.getHttpdTomcatContexts()) {
        out.print("    <Context path=\"").textInXmlAttribute(htc.getPath()).print("\" docBase=\"")
            .textInXmlAttribute(htc.getDocBase()).print("\" debug=\"").textInXmlAttribute(htc.getDebugLevel())
            .print("\" reloadable=\"").textInXmlAttribute(htc.isReloadable()).print("\" />\n");
      }
      out.print("  </ContextManager>\n"
          + "</Server>\n");
    }
    return bout.toByteArray();
  }
}
